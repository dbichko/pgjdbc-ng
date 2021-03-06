/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2011, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */
package com.impossibl.postgres.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import junit.framework.TestCase;



public class SavepointTest extends TestCase {

	private Connection _conn;

	public SavepointTest(String name) {
		super(name);
	}

	protected void setUp() throws Exception {
		_conn = TestUtil.openDB();
		TestUtil.createTable(_conn, "savepointtable", "id int primary key");
		_conn.setAutoCommit(false);
	}

	protected void tearDown() throws SQLException {
		_conn.setAutoCommit(true);
		TestUtil.dropTable(_conn, "savepointtable");
		TestUtil.closeDB(_conn);
	}

	private void addRow(int id) throws SQLException {
		PreparedStatement pstmt = _conn.prepareStatement("INSERT INTO savepointtable VALUES (?)");
		pstmt.setInt(1, id);
		pstmt.executeUpdate();
		pstmt.close();
	}

	private int countRows() throws SQLException {
		Statement stmt = _conn.createStatement();
		ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM savepointtable");
		rs.next();
		int count = rs.getInt(1);
		rs.close();
		return count;
	}

	public void testAutoCommitFails() throws SQLException {

		_conn.setAutoCommit(true);

		try {
			_conn.setSavepoint();
			fail("Can't create a savepoint with autocommit.");
		}
		catch(SQLException sqle) {
		}

		try {
			_conn.setSavepoint("spname");
			fail("Can't create a savepoint with autocommit.");
		}
		catch(SQLException sqle) {
		}
	}

	public void testCantMixSavepointTypes() throws SQLException {

		Savepoint namedSavepoint = _conn.setSavepoint("named");
		Savepoint unNamedSavepoint = _conn.setSavepoint();

		try {
			namedSavepoint.getSavepointId();
			fail("Can't get id from named savepoint.");
		}
		catch(SQLException sqle) {
		}

		try {
			unNamedSavepoint.getSavepointName();
			fail("Can't get name from unnamed savepoint.");
		}
		catch(SQLException sqle) {
		}

	}

	public void testRollingBackToSavepoints() throws SQLException {

		Savepoint empty = _conn.setSavepoint();
		addRow(1);
		Savepoint onerow = _conn.setSavepoint("onerow");
		addRow(2);

		assertEquals(2, countRows());
		_conn.rollback(onerow);
		assertEquals(1, countRows());
		_conn.rollback(empty);
		assertEquals(0, countRows());
	}

	public void testGlobalRollbackWorks() throws SQLException {

		_conn.setSavepoint();
		addRow(1);
		_conn.setSavepoint("onerow");
		addRow(2);

		assertEquals(2, countRows());
		_conn.rollback();
		assertEquals(0, countRows());
	}

	public void testContinueAfterError() throws SQLException {

		addRow(1);
		Savepoint savepoint = _conn.setSavepoint();
		try {
			addRow(1);
			fail("Should have thrown duplicate key exception");
		}
		catch(SQLException sqle) {
			_conn.rollback(savepoint);
		}

		assertEquals(1, countRows());
		addRow(2);
		assertEquals(2, countRows());
	}

	public void testReleaseSavepoint() throws SQLException {

		Savepoint savepoint = _conn.setSavepoint("mysavepoint");
		_conn.releaseSavepoint(savepoint);
		try {
			savepoint.getSavepointName();
			fail("Can't use savepoint after release.");
		}
		catch(SQLException sqle) {
		}

		savepoint = _conn.setSavepoint();
		_conn.releaseSavepoint(savepoint);
		try {
			savepoint.getSavepointId();
			fail("Can't use savepoint after release.");
		}
		catch(SQLException sqle) {
		}
	}

	public void testComplicatedSavepointName() throws SQLException {

		Savepoint savepoint = _conn.setSavepoint("name with spaces + \"quotes\"");
		_conn.rollback(savepoint);
		_conn.releaseSavepoint(savepoint);
	}

	public void testRollingBackToInvalidSavepointFails() throws SQLException {

		Savepoint sp1 = _conn.setSavepoint();
		Savepoint sp2 = _conn.setSavepoint();

		_conn.rollback(sp1);
		try {
			_conn.rollback(sp2);
			fail("Can't rollback to a savepoint that's invalid.");
		}
		catch(SQLException sqle) {
		}
	}

	public void testRollbackMultipleTimes() throws SQLException {

		addRow(1);
		Savepoint savepoint = _conn.setSavepoint();

		addRow(2);
		_conn.rollback(savepoint);
		assertEquals(1, countRows());

		_conn.rollback(savepoint);
		assertEquals(1, countRows());

		addRow(2);
		_conn.rollback(savepoint);
		assertEquals(1, countRows());

		_conn.releaseSavepoint(savepoint);
		assertEquals(1, countRows());
	}

}
