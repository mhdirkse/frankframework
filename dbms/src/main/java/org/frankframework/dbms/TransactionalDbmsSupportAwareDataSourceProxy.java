/*
   Copyright 2021-2023 WeAreFrank!

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
package org.frankframework.dbms;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import lombok.extern.log4j.Log4j2;

import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * DataSource that is aware of the database metadata.
 * Fetches the metadata once and caches them.
 */

@Log4j2
public class TransactionalDbmsSupportAwareDataSourceProxy extends TransactionAwareDataSourceProxy {
	private Map<String, String> metadata;
	private String destinationName = null;

	public TransactionalDbmsSupportAwareDataSourceProxy(DataSource delegate) {
		super(delegate);
	}

	public Map<String, String> getMetaData() throws SQLException {
		if(metadata == null) {
			log.debug("populating metadata from getMetaData");
			try (Connection connection = getConnection()) {
				populateMetadata(connection);
			}
		}
		return metadata;
	}

	/**
	 * Should only be called once, either on the first {@link #getConnection()} or when explicitly requested {@link #getMetaData()}.
	 */
	private void populateMetadata(Connection connection) throws SQLException {
		Map<String, String> databaseMetadata = new HashMap<>();
		DatabaseMetaData md = connection.getMetaData();
		databaseMetadata.put("catalog", connection.getCatalog());

		databaseMetadata.put("user", md.getUserName());
		databaseMetadata.put("url", md.getURL());
		databaseMetadata.put("product", md.getDatabaseProductName());
		databaseMetadata.put("product-version", md.getDatabaseProductVersion());
		databaseMetadata.put("driver", md.getDriverName());
		databaseMetadata.put("driver-version", md.getDriverVersion());

		this.metadata = databaseMetadata;
	}

	public String getDestinationName() throws SQLException {
		if(destinationName == null) {
			StringBuilder builder = new StringBuilder();
			builder.append(getMetaData().get("url"));

			String catalog = getMetaData().get("catalog");
			if(catalog != null) builder.append("/"+catalog);

			destinationName = builder.toString();
		}
		return destinationName;
	}

	@Override
	public Connection getConnection() throws SQLException {
		Connection conn = super.getConnection();
		if(metadata == null) {
			log.debug("populating metadata from getConnection");
			populateMetadata(conn);
		}

		return conn;
	}

	@Override
	public String toString() {
		if(metadata != null && log.isInfoEnabled()) {
			return getInfo();
		}
		return obtainTargetDataSource().toString();
	}

	public String getInfo() {
		StringBuilder builder = new StringBuilder();

		if(metadata != null) {
			builder.append("user ["+metadata.get("user")+"]");
			builder.append(" url ["+metadata.get("url")+"]");
			builder.append(" product ["+metadata.get("product")+"]");
			builder.append(" product version ["+metadata.get("product-version")+"]");
			builder.append(" driver ["+metadata.get("driver")+"]");
			builder.append(" driver version ["+metadata.get("driver-version")+"]");
		}
		builder.append(" datasource ["+obtainTargetDataSource().toString()+"]");

		return builder.toString();
	}
}
