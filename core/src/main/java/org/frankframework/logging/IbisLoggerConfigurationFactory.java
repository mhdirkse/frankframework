/*
   Copyright 2020, 2022-2023 WeAreFrank!

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
package org.frankframework.logging;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;

import org.frankframework.util.StreamUtil;
import org.frankframework.util.StringResolver;

/**
 * This ConfigurationFactory is loaded after the log4j2.properties file has been initialised.
 * Both Configurations are then combined via a CompositeConfiguration
 *
 * @author Murat Kaan Meral
 * @author Niels Meijer
 */
//@Order(1000)
//@Plugin(name = "IbisLoggerConfigurationFactory", category = ConfigurationFactory.CATEGORY)
public class IbisLoggerConfigurationFactory extends ConfigurationFactory {
	public static final String LOG_PREFIX = "IbisLoggerConfigurationFactory class ";
	private static final String LOG4J_PROPS_FILE = "log4j4ibis.properties";
	private static final String DS_PROPERTIES_FILE = "DeploymentSpecifics.properties";
	public static final String LOG4J_PROPERTY_REGEX = "(?<=\\$\\{ctx:)([^:].*?)(?=(:-[^}]*+)?})";

	static {
		System.setProperty("java.util.logging.manager", org.apache.logging.log4j.jul.LogManager.class.getCanonicalName());
	}

	/**
	 * Hierarchy of log directories to search for. Strings will be split by "/".
	 * Before "/" split will be assumed to be a property, and after split will be a sub-directory.
	 */
	private static final String[] logDirectoryHierarchy = new String[] {
			"site.logdir",
			"user.dir/logs",
			"user.dir/log",
			"jboss.server.base.dir/log",
			"wtp.deploy/../logs",
			"catalina.base/logs"
	};

	@Override
	protected String[] getSupportedTypes() {
		return new String[] {".xml"};
	}

	@Override
	public Configuration getConfiguration(LoggerContext loggerContext, ConfigurationSource source) {
		try {
			setLogDir();
			setLevel();

			String configuration = readLog4jConfiguration(source.getInputStream());
			Properties properties = getProperties();
			Map<String, String> substitutions = populateThreadContextProperties(configuration, properties);
			ThreadContext.putAll(substitutions); // Only add the substituted variables to the ThreadContext
			//Perhaps it's an idea to clear the ThreadContext after the configuration has been loaded.

			initLogExpressionHiding(properties);

			return new XmlConfiguration(loggerContext, source.resetInputStream()) { //We have to 'reset' the source as the old stream has been read.

				@Override // Add hashcode to toString() so we can differentiate the XmlConfigurations in the startup log
				public String toString() {
					return this.getClass().getSuperclass().getCanonicalName() + "@" + Integer.toHexString(this.hashCode()) + "[location=" + getConfigurationSource() + "]";
				}
			};
		} catch (IOException e) {
			System.err.println(LOG_PREFIX + "unable to configure Log4J2");
			throw new IllegalStateException(LOG_PREFIX + "unable to configure Log4J2", e);
		}
	}

	@Nonnull
	protected static Map<String, String> populateThreadContextProperties(final String configuration, final Properties properties) {
		Matcher m = Pattern.compile(LOG4J_PROPERTY_REGEX).matcher(configuration); //Look for properties in the Log4J2 XML
		Map<String, String> substitutions = new HashMap<>();
		while (m.find()) {
			String key = m.group(1);
			String value = resolveValueRecursively(properties, key);

			if(value != null) {
				substitutions.put(key, value);
			}
		}
		return substitutions;
	}

	private static void initLogExpressionHiding(Properties properties) {
		String logHideRegex = properties.getProperty("log.hideRegex");
		if (StringUtils.isNotBlank(logHideRegex)) {
			IbisMaskingLayout.addToGlobalReplace(logHideRegex);
		}
	}

	@Nullable
	private static String resolveValueRecursively(Properties properties, String key) {
		String value = properties.getProperty(key);
		if(StringUtils.isEmpty(value)) {
			return null;
		}

		if(StringResolver.needsResolution(value)) {
			value = StringResolver.substVars(value, properties);
		}

		if("log.dir".equals(key)) {
			value = fixLogDirectorySlashes(value);
		}

		return value;
	}

	@Nonnull
	private Properties getProperties() throws IOException {
		Properties log4jProperties = getProperties(LOG4J_PROPS_FILE);
		if(log4jProperties == null) {
			log4jProperties = new Properties();
			System.out.println(LOG_PREFIX + "did not find " + LOG4J_PROPS_FILE + ", leaving it up to log4j's default initialization procedure");
		}

		Properties dsProperties = getProperties(DS_PROPERTIES_FILE);
		if (dsProperties != null) {
			log4jProperties.putAll(dsProperties);
		}

		log4jProperties.putAll(System.getProperties()); //Set these after reading DeploymentSpecifics as we want to override the properties
		log4jProperties.putAll(System.getenv()); // let environment properties override system properties and appConstants
		setInstanceNameLc(log4jProperties); //Set instance.name.lc for log file names

		return log4jProperties;
	}

	private @Nullable Properties getProperties(String filename) throws IOException {
		URL url = this.getClass().getClassLoader().getResource(filename);
		if(url != null) {
			Properties properties = new Properties();
			try(InputStream is = url.openStream(); Reader reader = StreamUtil.getCharsetDetectingInputStreamReader(is)) {
				properties.load(reader);
			}
			return properties;
		}
		return null;
	}

	private static void setInstanceNameLc(Properties log4jProperties) {
		String instanceNameLowerCase = log4jProperties.getProperty("instance.name");
		if (instanceNameLowerCase != null) {
			instanceNameLowerCase = instanceNameLowerCase.toLowerCase();
		} else {
			instanceNameLowerCase = "ibis";
		}
		log4jProperties.setProperty("instance.name.lc", instanceNameLowerCase);
	}

	public static String readLog4jConfiguration(InputStream stream) throws IOException {
		char[] buff = new char[1024];
		Writer stringWriter = new StringWriter();
		try (Reader reader = new InputStreamReader(new BufferedInputStream(stream))) {
			int n;
			boolean checkVersionOnlyFirst1024Characters = true;
			while ((n = reader.read(buff))!=-1) {
				stringWriter.write(buff, 0, n);

				if(checkVersionOnlyFirst1024Characters) {
					//See if log4j2 prefix is somewhere in the first 1024 characters
					if(!stringWriter.toString().contains("<log4j2:Configuration")) {
						System.err.println(LOG_PREFIX + "did not recognize configuration format, unable to configure Log4j2. Please use the log4j2 layout in file log4j4ibis.xml");
						throw new IllegalStateException("Did not recognize configuration format, unable to configure Log4j2. Please use the log4j2 layout in file log4j4ibis.xml");
					}
					checkVersionOnlyFirst1024Characters = false;
				}
			}
			return stringWriter.toString();
		}
		finally {
			stringWriter.close();
			stream.close();
		}
	}

	/**
	 * Checks if log.dir property exists.
	 * Sets it with findLogDir function.
	 */
	private static void setLogDir() {
		if (System.getProperty("log.dir") == null) {
			File logDir = findLogDir();
			if (logDir != null) {
				System.setProperty("log.dir", fixLogDirectorySlashes(logDir.getPath()));
			} else {
				System.out.println(LOG_PREFIX + "did not find system property log.dir and unable to locate it automatically");
			}
		}
	}

	/**
	 * Replace backslashes because log.dir is used in log4j2.xml
	 * on which substVars is done (see below) which will replace
	 * double backslashes into one backslash and after that the same
	 * is done by Log4j:
	 * https://issues.apache.org/bugzilla/show_bug.cgi?id=22894
	 * */
	private static String fixLogDirectorySlashes(String directory) {
		return directory.replace("\\", "/");
	}

	/**
	 * Checks if the {@code log.level} is set in the system properties.
	 * If not set, sets it based on {@code dtap.stage}: When system property {@code dtap.stage}
	 * is {@code ACC} or {@code PRD} then the log level is set to {@code WARN}, otherwise to {@code DEBUG}.
	 */
	private static void setLevel() {
		if (System.getProperty("log.level") == null) {
			// In the log4j4ibis.xml the rootlogger contains the loglevel: ${log.level}
			// You can set this property in the log4j4ibis.properties, or as system property.
			// To make sure the IBIS can start up if no log.level property has been found, it has to be explicitly set
			String stage = System.getProperty("dtap.stage");
			String logLevel = "DEBUG";
			if("ACC".equalsIgnoreCase(stage) || "PRD".equalsIgnoreCase(stage)) {
				logLevel = "WARN";
			}
			System.setProperty("log.level", logLevel);
		}
	}

	/**
	 * Finds the first directory in the given hierarchy.
	 * logDirectoryHierarchy is an array of Strings.
	 *                  Strings will be split by "/" and before split will be assumed to be property,
	 *                  and after split will be the subdirectory.
	 * @return File object that is a directory. Or null, if no directories were found.
	 */
	private static File findLogDir() {
		for(String option : logDirectoryHierarchy) {
			int splitIndex = option.indexOf('/');

			String property = System.getProperty(option.substring(0, (splitIndex == -1) ? option.length() : splitIndex));
			if(property == null)
				continue;

			File dir;
			if(splitIndex == -1) {
				dir = new File(property);
			} else {
				dir = new File(property, option.substring(splitIndex));
			}

			if(dir.isDirectory())
				return dir;
		}
		return null;
	}
}
