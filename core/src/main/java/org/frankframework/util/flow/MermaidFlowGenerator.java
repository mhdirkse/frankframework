/*
   Copyright 2022-2023 WeAreFrank!

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
package org.frankframework.util.flow;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.transform.TransformerException;

import org.frankframework.core.IConfigurable;
import org.frankframework.core.Resource;
import org.frankframework.doc.ElementType;
import org.frankframework.doc.Protected;
import org.frankframework.filesystem.FileSystemListener;
import org.frankframework.filesystem.FileSystemSender;
import org.frankframework.senders.IbisJavaSender;
import org.frankframework.senders.IbisLocalSender;
import org.frankframework.util.StringUtil;
import org.frankframework.util.TransformerPool;
import org.frankframework.util.XmlUtils;
import org.frankframework.xml.SaxDocumentBuilder;
import org.frankframework.xml.SaxElementBuilder;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.extern.log4j.Log4j2;

/**
 * Flow generator to create MERMAID files
 */
@Log4j2
public class MermaidFlowGenerator implements IFlowGenerator {

	private static final String ADAPTER2MERMAID_XSLT = "/xml/xsl/adapter2mermaid.xsl";
	private static final String CONFIGURATION2MERMAID_XSLT = "/xml/xsl/configuration2mermaid.xsl";

	private final List<String> resourceMethods;
	private Document frankElements;

	private TransformerPool transformerPoolAdapter;
	private TransformerPool transformerPoolConfig;

	public MermaidFlowGenerator() {
		resourceMethods = List.of(
				"setAction", "setWsdl", "setSchema", "setSchemaLocation", "setDirection", "setOutputFormat",
				"setResponseRoot", "setXpathExpression", "setStyleSheetName", "setStyleSheetNameSessionKey"
			);
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		String frankElementsList = compileFrankElementList();
		frankElements = XmlUtils.buildDomDocument(new InputSource(new StringReader(frankElementsList)), true);

		Resource xsltSourceAdapter = Resource.getResource(ADAPTER2MERMAID_XSLT);
		transformerPoolAdapter = TransformerPool.getInstance(xsltSourceAdapter, 2);

		Resource xsltSourceConfig = Resource.getResource(CONFIGURATION2MERMAID_XSLT);
		transformerPoolConfig = TransformerPool.getInstance(xsltSourceConfig, 2);
	}

	private String compileFrankElementList() throws SAXException, ClassNotFoundException {
		try (SaxDocumentBuilder builder = new SaxDocumentBuilder("root")) {
			List<String> classNames = findAllFrankElements();
			for(String className : classNames) {
				Class<?> clazz = Class.forName(className);
				ElementType type = AnnotationUtils.findAnnotation(clazz, ElementType.class);
				if(type != null) {
					try (SaxElementBuilder classElement = builder.startElement(className)) {
						try (SaxElementBuilder typeElement = classElement.startElement("type")) {
							typeElement.addValue(type.value().name().toLowerCase());
						}
						try (SaxElementBuilder modifierElement = classElement.startElement("modifier")) {
							modifierElement.addValue(""+deduceModifier(clazz));
						}
						addResourceMethods(classElement, clazz.getMethods());
					}
				}
				else {
					log.debug("skipping class [{}]", clazz);
				}
			}
			builder.endElement();
			return builder.toString();
		}
	}

	/**
	 * Returns a classifier use by the Mermaid XSLT, which in turn is used to change the style in the diagram.
	 */
	private int deduceModifier(Class<?> clazz) {
		String packageName = clazz.getPackageName();
		if(packageName.contains(".http")) {
			return 0;
		} else if(packageName.contains(".jms") || packageName.contains(".esb")) {
			return 1;
		} else if(packageName.contains(".jdbc")) {
			return 2;
		} else if(FileSystemListener.class.isAssignableFrom(clazz) || FileSystemSender.class.isAssignableFrom(clazz)) {
			return 3;
		} else if(IbisJavaSender.class.isAssignableFrom(clazz) || IbisLocalSender.class.isAssignableFrom(clazz)) {
			return 4;
		} else if(packageName.contains(".sap")) {
			return 5;
		}
		return 7;
	}

	private void addResourceMethods(SaxElementBuilder element, Method[] methods) throws SAXException {
		for(Method method : methods) {
			String methodName = method.getName();
			if(method.getParameterTypes().length == 1 && resourceMethods.contains(methodName)) {
				String attributeName = StringUtil.lcFirst(methodName.substring(3));
				addAttribute(element, attributeName);
			}
		}
	}

	private void addAttribute(SaxElementBuilder element, String attributeValue) throws SAXException {
		try (SaxElementBuilder attributeElement = element.startElement("attribute")) {
			attributeElement.addAttribute("name", attributeValue);
		}
	}

	private List<String> findAllFrankElements() {
		BeanDefinitionRegistry beanDefinitionRegistry = new SimpleBeanDefinitionRegistry();
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(beanDefinitionRegistry);
		scanner.setIncludeAnnotationConfig(false);
		scanner.resetFilters(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(IConfigurable.class));
		scanner.addIncludeFilter(new AnnotationTypeFilter(ElementType.class));
		scanner.addExcludeFilter(this::matchesTestClassPath); //Exclude test classpath
		scanner.addExcludeFilter((i,e) -> i.getClassMetadata().getClassName().contains("$")); //Exclude inner classes
		scanner.addExcludeFilter(new AnnotationTypeFilter(Protected.class)); //Exclude protected FrankElements

		BeanNameGenerator beanNameGenerator = new AnnotationBeanNameGenerator() {
			@Override
			protected String buildDefaultBeanName(BeanDefinition definition) {
				String beanClassName = definition.getBeanClassName();
				Assert.state(beanClassName != null, "No bean class name set");
				return beanClassName;
			}
		};
		scanner.setBeanNameGenerator(beanNameGenerator);

		int numberOfBeans = scanner.scan("org.frankframework");
		log.debug("found [{}] beans registered", numberOfBeans);

		String[] bdn = scanner.getRegistry().getBeanDefinitionNames();
		return Arrays.asList(bdn);
	}

	private boolean matchesTestClassPath(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
		return reader.getResource().getURI().toString().contains("/test-classes/");
	}

	@Override
	public void generateFlow(String xml, OutputStream outputStream) throws FlowGenerationException {
		try {
			String flow = generateMermaid(xml);

			outputStream.write(flow.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new FlowGenerationException(e);
		}
	}

	protected String generateMermaid(String xml) throws FlowGenerationException {
		try {
			Map<String, Object> xsltParams = new HashMap<>(1);//frankElements
			xsltParams.put("frankElements", frankElements);
			if(xml.startsWith("<adapter")) {
				return transformerPoolAdapter.transform(xml, xsltParams);
			} else {
				return transformerPoolConfig.transform(xml, xsltParams);
			}
		} catch (IOException | TransformerException | SAXException e) {
			throw new FlowGenerationException("error transforming [xml] to [mermaid]", e);
		}
	}

	@Override
	public String getFileExtension() {
		return "mmd";
	}

	@Override
	public MediaType getMediaType() {
		return MediaType.TEXT_PLAIN;
	}

	@Override
	public void destroy() {
		if(transformerPoolAdapter != null) {
			transformerPoolAdapter.close();
		}

		if(transformerPoolConfig != null) {
			transformerPoolConfig.close();
		}
		frankElements = null;
	}
}
