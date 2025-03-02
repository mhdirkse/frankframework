/*
   Copyright 2013 Nationale-Nederlanden, 2020-2023 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.frankframework.extensions.esb;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;
import lombok.Setter;

import org.frankframework.configuration.Configuration;
import org.frankframework.configuration.ConfigurationException;
import org.frankframework.configuration.classloaders.DirectoryClassLoader;
import org.frankframework.core.Adapter;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLine;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.core.Resource;
import org.frankframework.doc.Category;
import org.frankframework.http.RestListenerUtils;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.receivers.Receiver;
import org.frankframework.soap.WsdlGenerator;
import org.frankframework.stream.Message;
import org.frankframework.util.Dir2Xml;
import org.frankframework.util.FileUtils;
import org.frankframework.util.StreamUtil;
import org.frankframework.util.TransformerPool;
import org.frankframework.util.TransformerPool.OutputType;
import org.frankframework.util.XmlUtils;

@Category("NN-Special")
public class WsdlGeneratorPipe extends FixedForwardPipe {

	private @Getter @Setter String sessionKey = "file";
	private @Getter @Setter String filenameSessionKey = "fileName";
	private @Getter @Setter String propertiesFileName = "wsdl.properties";

	@Override
	public PipeRunResult doPipe(Message message, PipeLineSession session) throws PipeRunException {
		Message fileInSession = session.getMessage(getSessionKey());
		if (Message.isNull(fileInSession)) {
			throw new PipeRunException(this, "got null value from session under key [" + getSessionKey() + "]");
		}

		File tempDirectoryBase;
		String fileName;

		try (InputStream inputStream = fileInSession.asInputStream()){
			tempDirectoryBase = FileUtils.getTempDirectory("WsdlGeneratorPipe");
			fileName = session.getString(getFilenameSessionKey());
			if (FileUtils.extensionEqualsIgnoreCase(fileName, "zip")) {
				FileUtils.unzipStream(inputStream, tempDirectoryBase);
			} else {
				File file = new File(tempDirectoryBase, fileName);
				StreamUtil.streamToFile(inputStream, file);
				Files.delete(file.toPath());
			}
		} catch (IOException e) {
			throw new PipeRunException(this, "Exception on uploading and unzipping/writing file", e);
		}

		File propertiesFile = new File(tempDirectoryBase, getPropertiesFileName());
		PipeLine pipeLine;
		ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
		try {
			// A DirectoryClassloader is used to create a new 'dummy' pipeline, see createPipeLineFromPropertiesFile(String)
			// This method reads a properties file and xsd's (when present) to programmatically 'create' a pipeline.
			// The pipeline will then be used to generate a new WSDL file.

			DirectoryClassLoader directoryClassLoader = new DirectoryClassLoader(originalClassLoader);
			directoryClassLoader.setDirectory(tempDirectoryBase.getPath());
			directoryClassLoader.setBasePath(".");
			directoryClassLoader.configure(getAdapter().getConfiguration().getIbisManager().getIbisContext(), "dummy");
			Thread.currentThread().setContextClassLoader(directoryClassLoader);

			if (propertiesFile.exists()) {
				pipeLine = createPipeLineFromPropertiesFile(propertiesFile);
			} else {
				File xsdFile = FileUtils.getFirstFile(tempDirectoryBase);
				pipeLine = createPipeLineFromXsdFile(xsdFile);
			}
		} catch (Exception e) {
			throw new PipeRunException(this, "Exception on generating wsdl", e);
		} finally {
			if (originalClassLoader != null) {
				Thread.currentThread().setContextClassLoader(originalClassLoader);
			}
		}

		Object result;
		try {
			Adapter adapter = new Adapter();
			Configuration configuration = new Configuration();
			configuration.setClassLoader(getConfigurationClassLoader());
			adapter.setConfiguration(configuration);
			String fileBaseName = FileUtils.getBaseName(fileName).replace(" ", "_");
			adapter.setName(fileBaseName);
			Receiver receiver = new Receiver();
			EsbJmsListener esbJmsListener = new EsbJmsListener();
			esbJmsListener.setQueueConnectionFactoryName("jms/qcf_" + fileBaseName);
			esbJmsListener.setDestinationName("jms/dest_" + fileBaseName);
			receiver.setListener(esbJmsListener);
			adapter.registerReceiver(receiver);
			adapter.setPipeLine(pipeLine);
			String generationInfo = "at " + RestListenerUtils.retrieveRequestURL(session);
			WsdlGenerator wsdl = new WsdlGenerator(pipeLine, generationInfo);
			wsdl.setIndent(true);
			wsdl.init();
			File wsdlDir = FileUtils.createTempDirectory(tempDirectoryBase);
			// zip (with includes)
			File zipOutFile = new File(wsdlDir, wsdl.getFilename() + ".zip");
			File fullWsdlOutFile = new File(wsdlDir, wsdl.getFilename() + ".wsdl");
			try (OutputStream zipOut = Files.newOutputStream(zipOutFile.toPath());
					OutputStream fullWsdlOut = Files.newOutputStream(fullWsdlOutFile.toPath())) {
				wsdl.setUseIncludes(true);
				wsdl.zip(zipOut, null);
				// full wsdl (without includes)
				wsdl.setUseIncludes(false);
				wsdl.wsdl(fullWsdlOut, null);
				Dir2Xml dx = new Dir2Xml();
				dx.setPath(wsdlDir.getPath());
				result = dx.getDirList();
			}
		} catch (Exception e) {
			throw new PipeRunException(this, "Exception on generating wsdl", e);
		}
		return new PipeRunResult(getSuccessForward(), result);
	}

	private PipeLine createPipeLineFromPropertiesFile(File propertiesFile) throws IOException, ConfigurationException {
		Properties props = new Properties();
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(propertiesFile);
			props.load(fis);
		} finally {
			try {
				if (fis != null) {
					fis.close();
				}
			} catch (IOException e) {
				log.warn("exception closing inputstream", e);
			}
		}

		PipeLine pipeLine = new PipeLine();
		String inputXsd = null;
		if (props.containsKey("input.xsd")) {
			inputXsd = props.getProperty("input.xsd");
			String inputNamespace = props.getProperty("input.namespace");
			String inputRoot = props.getProperty("input.root");
			String inputCmhString = props.getProperty("input.cmh", "1");
			int inputCmh = Integer.parseInt(inputCmhString);
			File inputXsdFile = new File(propertiesFile.getParent(), inputXsd);
			EsbSoapValidator inputValidator = createValidator(inputXsdFile,
					inputNamespace, inputRoot, 1, inputCmh, pipeLine);
			pipeLine.setInputValidator(inputValidator);
		}
		if (props.containsKey("output.xsd")) {
			String outputXsd = props.getProperty("output.xsd");
			String outputNamespace = props.getProperty("output.namespace");
			String outputRoot = props.getProperty("output.root");
			String outputCmhString = props.getProperty("output.cmh", "1");
			int outputCmh = Integer.parseInt(outputCmhString);
			File outputXsdFile = new File(propertiesFile.getParent(), outputXsd);
			int rootPosition;
			if (inputXsd != null && inputXsd.equalsIgnoreCase(outputXsd)) {
				rootPosition = 2;
			} else {
				rootPosition = 1;
			}
			EsbSoapValidator outputValidator = createValidator(outputXsdFile, outputNamespace, outputRoot, rootPosition, outputCmh, pipeLine);
			pipeLine.setOutputValidator(outputValidator);
		}
		return pipeLine;
	}

	private PipeLine createPipeLineFromXsdFile(File xsdFile)
			throws ConfigurationException {
		PipeLine pipeLine = new PipeLine();
		EsbSoapValidator inputValidator;
		inputValidator = createValidator(xsdFile, null, null, 1, 1, pipeLine);
		pipeLine.setInputValidator(inputValidator);

		String countRoot = null;
		try {
			String countRootXPath = "count(*/*[local-name()='element'])";
			TransformerPool tp = TransformerPool.getInstance(XmlUtils.createXPathEvaluatorSource(countRootXPath, OutputType.TEXT));
			Resource xsdResource = Resource.getResource(xsdFile.getPath());
			countRoot = tp.transform(xsdResource.asSource());
			if (StringUtils.isNotEmpty(countRoot)) {
				log.debug("counted [" + countRoot + "] root elements in xsd file [" + xsdFile.getName() + "]");
				int cr = Integer.parseInt(countRoot);
				if (cr > 1) {
					EsbSoapValidator outputValidator;
					outputValidator = createValidator(xsdFile, null, null, 2, 1, pipeLine);
					pipeLine.setOutputValidator(outputValidator);
				}
			}
		} catch (Exception e) {
			throw new ConfigurationException(e);
		}
		return pipeLine;
	}

	private EsbSoapValidator createValidator(File xsdFile, String namespace,
			String root, int rootPosition, int cmhVersion, PipeLine pipeLine) throws ConfigurationException {
		if (xsdFile != null) {
			EsbSoapValidator esbSoapValidator = new EsbSoapValidator();
			esbSoapValidator.setWarn(false);
			esbSoapValidator.setCmhVersion(cmhVersion);

			Resource xsdResource = Resource.getResource(xsdFile.getPath());
			if (StringUtils.isEmpty(namespace)) {
				String xsdTargetNamespace = null;
				try {
					TransformerPool tp = TransformerPool.getInstance(XmlUtils.createXPathEvaluatorSource("*/@targetNamespace", OutputType.TEXT));
					xsdTargetNamespace = tp.transform(xsdResource.asSource());
					if (StringUtils.isNotEmpty(xsdTargetNamespace)) {
						log.debug("found target namespace [" + xsdTargetNamespace + "] in xsd file [" + xsdFile.getName() + "]");
					} else {
						// default namespace to prevent
						// "(IllegalArgumentException) The schema attribute isn't supported"
						xsdTargetNamespace = "urn:wsdlGenerator";
						log.warn("could not find target namespace in xsd file [" + xsdFile.getName() + "], assuming namespace [" + xsdTargetNamespace + "]");
					}
				} catch (Exception e) {
					throw new ConfigurationException(e);
				}
				if (StringUtils.isEmpty(xsdTargetNamespace)) {
					esbSoapValidator.setSchema(xsdFile.getName());
				} else {
					esbSoapValidator.setSchemaLocation(xsdTargetNamespace
							+ "\t" + xsdFile.getName());
					esbSoapValidator.setAddNamespaceToSchema(true);
				}
			} else {
				esbSoapValidator.setSchemaLocation(namespace + "\t"
						+ xsdFile.getName());
				esbSoapValidator.setAddNamespaceToSchema(true);
			}

			if (StringUtils.isEmpty(root)) {
				String xsdRoot = null;
				try {
					String rootXPath = "*/*[local-name()='element'][" + rootPosition + "]/@name";
					TransformerPool tp = TransformerPool.getInstance(XmlUtils.createXPathEvaluatorSource(rootXPath, OutputType.TEXT));
					xsdRoot = tp.transform(xsdResource.asSource());
					if (StringUtils.isNotEmpty(xsdRoot)) {
						log.debug("found root element [" + xsdRoot + "] in xsd file [" + xsdFile.getName() + "]");
						esbSoapValidator.setSoapBody(xsdRoot);
					}
				} catch (Exception e) {
					throw new ConfigurationException(e);
				}
			} else {
				esbSoapValidator.setSoapBody(root);
			}

			esbSoapValidator.setForwardFailureToSuccess(true);
			PipeForward pf = new PipeForward();
			pf.setName(PipeForward.SUCCESS_FORWARD_NAME);
			esbSoapValidator.registerForward(pf);
			esbSoapValidator.setPipeLine(pipeLine);
			esbSoapValidator.configure();
			return esbSoapValidator;
		}
		return null;
	}
}
