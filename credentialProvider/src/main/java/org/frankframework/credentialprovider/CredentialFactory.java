/*
   Copyright 2021 Nationale-Nederlanden, 2022-2023 WeAreFrank!

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
package org.frankframework.credentialprovider;

import java.util.Collection;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.frankframework.credentialprovider.util.CredentialConstants;

public class CredentialFactory {
	protected Logger log = Logger.getLogger(this.getClass().getCanonicalName());

	private static final String CREDENTIAL_FACTORY_KEY="credentialFactory.class";
	private static final String CREDENTIAL_FACTORY_OPTIONAL_PREFIX_KEY="credentialFactory.optionalPrefix";
	private static final String DEFAULT_CREDENTIAL_FACTORY=FileSystemCredentialFactory.class.getName();

	public static final String LEGACY_PACKAGE_NAME = "nl.nn.credentialprovider.";
	public static final String ORG_FRANKFRAMEWORK_PACKAGE_NAME = "org.frankframework.credentialprovider.";

	private static String optionalPrefix;

	private ICredentialFactory delegate;

	private static CredentialFactory self;

	static {
		optionalPrefix = CredentialConstants.getInstance().getProperty(CREDENTIAL_FACTORY_OPTIONAL_PREFIX_KEY);
		if (optionalPrefix != null) {
			optionalPrefix = optionalPrefix.toLowerCase();
		}
	}

	public static synchronized CredentialFactory getInstance() {
		if (self==null) {
			self=new CredentialFactory();
		}
		return self;
	}

	private CredentialFactory() {
		String factoryClassName = CredentialConstants.getInstance().getProperty(CREDENTIAL_FACTORY_KEY);

		// Legacy support for old package names; to be removed in Frank!Framework 8.1 or later
		if (StringUtils.isNotEmpty(factoryClassName) && factoryClassName.contains(LEGACY_PACKAGE_NAME)) {
			factoryClassName = factoryClassName.replace(LEGACY_PACKAGE_NAME, ORG_FRANKFRAMEWORK_PACKAGE_NAME);
		}
		if (tryFactory(factoryClassName)) {
			return;
		}
		if (tryFactory(DEFAULT_CREDENTIAL_FACTORY)) {
			return;
		}
		log.warning("No CredentialFactory installed");
	}

	// package private method to force delegate for test purposes
	void forceDelegate(ICredentialFactory delegate) {
		this.delegate=delegate;
	}

	private boolean tryFactory(String factoryClassName) {
		if (StringUtils.isNotEmpty(factoryClassName)) {
			log.info("trying to configure CredentialFactory ["+factoryClassName+"]");
			try {
				Class<ICredentialFactory> factoryClass = (Class<ICredentialFactory>)Class.forName(factoryClassName);
				delegate = factoryClass.newInstance();
				delegate.initialize();
				log.info("installed CredentialFactory ["+factoryClassName+"]");
				return true;
			} catch (Exception e) {
				log.log(Level.WARNING, "Cannot instantiate CredentialFactory ["+factoryClassName+"] (" + e.getClass().getTypeName() + "): " + e.getMessage());
			}
		}
		return false;
	}

	private static String findAlias(String rawAlias) {
		if (optionalPrefix!=null && rawAlias!=null && rawAlias.toLowerCase().startsWith(optionalPrefix)) {
			return rawAlias.substring(optionalPrefix.length());
		}
		return rawAlias;
	}

	public static boolean hasCredential(String rawAlias) {
		ICredentialFactory delegate = getInstance().delegate;
		return delegate==null || delegate.hasCredentials(findAlias(rawAlias));
	}

	public static ICredentials getCredentials(String rawAlias, Supplier<String> defaultUsernameSupplier, Supplier<String> defaultPasswordSupplier) {
		ICredentialFactory delegate = getInstance().delegate;
		if (delegate!=null) {
			ICredentials result = delegate.getCredentials(findAlias(rawAlias), defaultUsernameSupplier, defaultPasswordSupplier);
			if (result!=null) {
				return result;
			}
		}
		return new Credentials(findAlias(rawAlias), defaultUsernameSupplier, defaultPasswordSupplier);
	}

	public static Collection<String> getConfiguredAliases() throws Exception {
		ICredentialFactory delegate = getInstance().delegate;
		return delegate!=null ? delegate.getConfiguredAliases() : null;
	}

}
