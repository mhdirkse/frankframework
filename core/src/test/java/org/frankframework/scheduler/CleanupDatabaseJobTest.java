package org.frankframework.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;

import org.frankframework.core.Adapter;
import org.frankframework.core.PipeLine;
import org.frankframework.dbms.Dbms;
import org.frankframework.dbms.JdbcException;
import org.frankframework.jdbc.JdbcTestBase;
import org.frankframework.jdbc.JdbcTransactionalStorage;
import org.frankframework.pipes.MessageSendingPipe;
import org.frankframework.receivers.Receiver;
import org.frankframework.scheduler.job.CleanupDatabaseJob;
import org.frankframework.scheduler.job.IJob;
import org.frankframework.util.AppConstants;
import org.frankframework.util.DbmsUtil;
import org.frankframework.util.JdbcUtil;
import org.frankframework.util.Locker;
import org.junit.Before;
import org.junit.Test;

public class CleanupDatabaseJobTest extends JdbcTestBase {

	private CleanupDatabaseJob jobDef;
	private JdbcTransactionalStorage<Serializable> storage;
	private final String cleanupJobName="CleanupDB";
	private final String txStorageTableName="NOT_IBISLOCK_TABLE";

	@Override
	@Before
	public void setup() throws Exception {
		super.setup();

		System.setProperty("tableName", "IBISLOCK"); //Lock table must exist
		runMigrator(TEST_CHANGESET_PATH);

		System.setProperty("tableName", txStorageTableName); //Actual JdbcTXStorage table
		runMigrator(TEST_CHANGESET_PATH);

		//noinspection unchecked
		storage = getConfiguration().createBean(JdbcTransactionalStorage.class);
		storage.setName("test-cleanupDB");
		storage.setType("A");
		storage.setSlotId("dummySlotId");
		storage.setTableName(txStorageTableName);
		storage.setSequenceName("SEQ_"+txStorageTableName);
		storage.setDatasourceName(getDataSourceName());

		if (getConfiguration().getScheduledJob("MockJob") == null) {
			IJob mockJob = mock(IJob.class);
			Locker mockLocker = mock(Locker.class);
			when(mockLocker.getDatasourceName()).thenAnswer(invocation -> getDataSourceName());
			when(mockJob.getLocker()).thenReturn(mockLocker);
			when(mockJob.getName()).thenReturn("MockJob");

			getConfiguration().getScheduleManager().registerScheduledJob(mockJob);
		}

		if (getConfiguration().getRegisteredAdapter("MockAdapter") == null) {
			Adapter mockAdapter = mock(Adapter.class);
			when(mockAdapter.getName()).thenReturn("MockAdapter");

			PipeLine pipeLine = new PipeLine();
			MessageSendingPipe mockPipe = mock(MessageSendingPipe.class);
			Locker mockLocker = mock(Locker.class);
			when(mockLocker.getDatasourceName()).thenAnswer(invocation -> getDataSourceName());
			when(mockPipe.getLocker()).thenReturn(mockLocker);
			when(mockPipe.getName()).thenReturn("MockPipe");
			when(mockPipe.getMessageLog()).thenReturn(storage);
			pipeLine.addPipe(mockPipe);
			when(mockAdapter.getPipeLine()).thenReturn(pipeLine);

			Receiver<?> mockReceiver = mock(Receiver.class);
			when(mockReceiver.getMessageLog()).thenReturn(storage);
			when(mockReceiver.getName()).thenReturn("MockReceiver");
			when(mockAdapter.getReceivers()).thenReturn(Collections.singletonList(mockReceiver));
			getConfiguration().registerAdapter(mockAdapter);
		}

		// Ensure we have an IbisManager via side effects of method
		//noinspection ResultOfMethodCallIgnored
		getConfiguration().getIbisManager();

		jobDef = new CleanupDatabaseJob();

		getConfiguration().autowireByName(jobDef);
	}

	@Test
	public void testCleanupDatabaseJobMaxRowsZero() throws Exception {
		jobDef.setName(cleanupJobName);
		jobDef.configure();
		prepareInsertQuery(1);

		// set max rows to 0
		AppConstants.getInstance().setProperty("cleanup.database.maxrows", "0");

		assertTrue(jobDef.beforeExecuteJob());
		jobDef.execute();

		assertEquals(0, getCount());
	}

	@Test
	public void testCleanupDatabaseJob() throws Exception {
		jobDef.setName(cleanupJobName);
		jobDef.configure();

		prepareInsertQuery(5);

		assertTrue(jobDef.beforeExecuteJob());
		jobDef.execute();

		assertEquals(0, getCount());
	}

	private void prepareInsertQuery(int numRows) throws Exception {
		Date date = new Date();
		Date expiryDate = new Date(date.getTime() - 3600 * 1000 * 24);
		StringBuilder sb = new StringBuilder("");
		for(int i = 1; i <= numRows; i++) {
			if(dbmsSupport.getDbms() == Dbms.ORACLE) {
				sb.append("SELECT "+i+", 'A', 'test', 'localhost', 'messageId', 'correlationId', "+dbmsSupport.getDatetimeLiteral(date)+", 'comments', "+dbmsSupport.getDatetimeLiteral(expiryDate)+", 'label' FROM DUAL");
			} else {
				sb.append("(");
				if(dbmsSupport.autoIncrementKeyMustBeInserted()) {
					sb.append(i+",");
				}
				sb.append("'A', 'test', 'localhost', 'messageId', 'correlationId', "+dbmsSupport.getDatetimeLiteral(date)+", 'comments', "+dbmsSupport.getDatetimeLiteral(expiryDate)+", 'label')");
			}
			if(i != numRows) {
				if(dbmsSupport.getDbms() == Dbms.ORACLE) {
					sb.append(" UNION ALL \n");
				} else {
					sb.append(",");
				}
			}
		}
		if(dbmsSupport.getDbms() == Dbms.ORACLE) {
			sb.append(") SELECT * FROM valuesTable");
		}

		String query ="INSERT INTO "+txStorageTableName+" (" +
				(dbmsSupport.autoIncrementKeyMustBeInserted() ? storage.getKeyField()+"," : "")
				+ storage.getTypeField() + ","
				+ storage.getSlotIdField() + ","
				+ storage.getHostField() + ","
				+ storage.getIdField() + ","
				+ storage.getCorrelationIdField() + ","
				+ storage.getDateField() + ","
				+ storage.getCommentField() + ","
				+ storage.getExpiryDateField()  +","
				+ storage.getLabelField() + ")" + (dbmsSupport.getDbms() == Dbms.ORACLE ? " WITH valuesTable AS (" : " VALUES ")
				+ sb.toString();

		try(Connection connection = getConnection()) {
			JdbcUtil.executeStatement(connection, query);
		}

		// check insertion
		assertEquals(numRows, getCount());
	}

	private int getCount() throws SQLException, JdbcException {
		return getCount(txStorageTableName);
	}

	private int getCount(String tableName) throws SQLException, JdbcException {
		try(Connection connection = getConnection()) {
			return DbmsUtil.executeIntQuery(connection, "SELECT count(*) from "+tableName);
		}
	}

	@Test
	public void testCleanupDatabaseJobMaxRowsOne() throws Exception {
		jobDef.setName(cleanupJobName);
		jobDef.configure();

		prepareInsertQuery(5);

		// to clean up 1 by 1
		AppConstants.getInstance().setProperty("cleanup.database.maxrows", "1");

		assertTrue(jobDef.beforeExecuteJob());
		jobDef.execute();

		assertEquals(0, getCount());
	}
}

