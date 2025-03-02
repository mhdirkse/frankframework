package org.frankframework.testutil.junit;

import static org.junit.platform.commons.util.AnnotationUtils.findRepeatableAnnotations;

import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.AnnotationUtils;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.extern.log4j.Log4j2;

/**
 * Works in tandem with {@link DatabaseTest} to execute a liquibase changeset before running a test.
 * Automatically cleans up the created tables after the test regardless of the state.
 *
 * @author Niels Meijer
 */
@Log4j2
public class JUnitLiquibaseExtension implements BeforeEachCallback, BeforeAllCallback, BeforeTestExecutionCallback {

	private static final String LIQUIBASE_CONFIGURATIONS = "LIQUIBASE_CONFIGURATIONS";
	private static final Namespace LIQUIBASE_NAMESPACE = Namespace.create(JUnitLiquibaseExtension.class);

	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		Method templateMethod = context.getRequiredTestMethod();
		boolean isDatabaseTest = AnnotationUtils.findAnnotation(templateMethod, DatabaseTest.class).isPresent();
		if(!isDatabaseTest) {
			throw new JUnitException("Not a @DatabaseTest");
		}

		List<WithLiquibase> annotations = findRepeatableAnnotations(templateMethod, WithLiquibase.class);
		storeAnnotations(context, annotations);
	}

	@Override
	public void beforeAll(ExtensionContext context) throws Exception {
		Class<?> templateClass = context.getRequiredTestClass();
		List<WithLiquibase> annotations = findRepeatableAnnotations(templateClass, WithLiquibase.class);
		storeAnnotations(context, annotations);
	}

	private void validate(List<WithLiquibase> annotations) {
		for(WithLiquibase liquibase : annotations) {
			String file = liquibase.file();
			URL url = JUnitLiquibaseExtension.class.getResource("/"+file);
			if(url == null) {
				throw new JUnitException("file ["+file+"] not found");
			}

			log.info("Running Liquibase file: {} tableName: {}", file, liquibase.tableName());
		}
	}

	private ExtensionContext.Store getStore(ExtensionContext context) {
		return context.getStore(LIQUIBASE_NAMESPACE);
	}

	@SuppressWarnings("unchecked")
	private List<WithLiquibase> getAnnotations(ExtensionContext context) {
		return getStore(context).get(LIQUIBASE_CONFIGURATIONS, List.class);
	}

	/**
	 * Store {@link WithLiquibase} annotations found on both class and method level in the JUnit context.
	 */
	private void storeAnnotations(ExtensionContext context, final List<WithLiquibase> annotations) {
		if(annotations.isEmpty()) {
			return;
		}

		validate(annotations);

		final List<WithLiquibase> toStore = new ArrayList<>();
		toStore.addAll(annotations);

		List<WithLiquibase> alreadyStoredAnnotations = getAnnotations(context);
		if(alreadyStoredAnnotations != null && !alreadyStoredAnnotations.isEmpty()) {
			toStore.addAll(alreadyStoredAnnotations);
		}

		getStore(context).put(LIQUIBASE_CONFIGURATIONS, toStore.stream().distinct().collect(Collectors.toList()));
	}

	@Override
	public void beforeTestExecution(ExtensionContext context) throws Exception {
		List<WithLiquibase> annotations = getAnnotations(context);
		if(annotations.isEmpty()) {
			return;
		}

		log.info("found {} migration scripts", annotations.size());

		DatabaseTestEnvironment dbTestEnv = DatabaseTestInvocationContext.getDatabaseTestEnvironment(context);
		if(dbTestEnv == null) {
			throw new JUnitException("no DatabaseTestEnvironment found");
		}

		AtomicInteger argumentIndex = new AtomicInteger();
		for(WithLiquibase annotation : annotations) {
			String file = annotation.file();
			String tableName = annotation.tableName();

			System.setProperty("tableName", tableName);
			Liquibase liquibase = runMigrator(file, dbTestEnv);

			//Store every instance in the 'Store' so it's auto-closed after the test has ran, even when it fails.
			getStore(context).put("liquibaseInstance#" + argumentIndex.incrementAndGet(), new CloseableArgument(liquibase));
		}
	}

	private Liquibase runMigrator(String changeLogFile, DatabaseTestEnvironment dbTestEnv) throws Exception {
		Connection connection = dbTestEnv.createNonTransactionalConnection();
		Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
		Liquibase liquibase = new Liquibase(changeLogFile, new ClassLoaderResourceAccessor(), db);
		liquibase.forceReleaseLocks();
		StringWriter out = new StringWriter(2048);
		liquibase.reportStatus(true, new Contexts(), out);
		log.info("Liquibase Database: {}, {}", liquibase.getDatabase().getDatabaseProductName(), liquibase.getDatabase().getDatabaseProductVersion());
		log.info("Liquibase Database connection: {}", liquibase.getDatabase());
		log.info("Liquibase changeset status:");
		log.info(out.toString());
		liquibase.update(new Contexts());
		return liquibase;
	}

	private static class CloseableArgument implements Store.CloseableResource {

		private final Liquibase liquibase;

		CloseableArgument(Liquibase liquibase) {
			this.liquibase = liquibase;
		}

		@Override
		public void close() throws Throwable {
			log.info("Closing Liquibase [{}] and releasing all connections", liquibase::getChangeLogFile);
			try {
				liquibase.dropAll();
			} catch(Exception e) {
				log.warn("Liquibase failed to drop all objects. Trying to rollback the changesets", e);
				liquibase.rollback(liquibase.getChangeSetStatuses(null, new LabelExpression()).size(), null);
			}
			liquibase.close();
		}
	}
}
