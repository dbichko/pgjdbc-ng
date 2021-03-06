package com.impossibl.postgres.jdbc;

import static com.impossibl.postgres.jdbc.ErrorUtils.chainWarnings;
import static com.impossibl.postgres.jdbc.Exceptions.NOT_ALLOWED_ON_PREP_STMT;
import static com.impossibl.postgres.jdbc.Exceptions.NOT_IMPLEMENTED;
import static com.impossibl.postgres.jdbc.Exceptions.PARAMETER_INDEX_OUT_OF_BOUNDS;
import static com.impossibl.postgres.jdbc.SQLTypeMetaData.getSQLType;
import static com.impossibl.postgres.jdbc.SQLTypeUtils.coerce;
import static com.impossibl.postgres.jdbc.SQLTypeUtils.mapSetType;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.impossibl.postgres.datetime.instants.Instants;
import com.impossibl.postgres.protocol.BindExecCommand;
import com.impossibl.postgres.protocol.PrepareCommand;
import com.impossibl.postgres.protocol.QueryCommand;
import com.impossibl.postgres.protocol.ResultField;
import com.impossibl.postgres.protocol.ServerObjectType;
import com.impossibl.postgres.types.Type;



class PGPreparedStatement extends PGStatement implements PreparedStatement {

	
	
	String sqlText;
	List<Type> parameterTypes;
	List<Object> parameterValues;
	List<List<Type>> batchParameterTypes;
	List<List<Object>> batchParameterValues;
	boolean wantsGeneratedKeys;
	boolean parsed;
	
	
	
	PGPreparedStatement(PGConnection connection, int type, int concurrency, int holdability, String name, String sqlText, int parameterCount) {
		super(connection, type, concurrency, holdability, name, null);
		this.sqlText = sqlText;
		this.parameterTypes = asList(new Type[parameterCount]);
		this.parameterValues = asList(new Object[parameterCount]);
	}

	public boolean getWantsGeneratedKeys() {
		return wantsGeneratedKeys;
	}

	public void setWantsGeneratedKeys(boolean wantsGeneratedKeys) {
		this.wantsGeneratedKeys = wantsGeneratedKeys;
	}

	/**
	 * Ensure the given parameter index is valid for this statement
	 * 
	 * @throws SQLException
	 * 					If the parameter index is out of bounds
	 */
	void checkParameterIndex(int idx) throws SQLException {
		
		if(idx < 1 || idx > parameterValues.size())
			throw PARAMETER_INDEX_OUT_OF_BOUNDS;
	}
	
	void set(int parameterIdx, Object val) throws SQLException {		
		checkClosed();
		checkParameterIndex(parameterIdx);
		
		parameterIdx -= 1;

		parameterValues.set(parameterIdx, val);
	}

	void set(int parameterIdx, Object val, int targetSQLType) throws SQLException {
		checkClosed();
		checkParameterIndex(parameterIdx);
		
		parameterIdx -= 1;

		Type paramType = parameterTypes.get(parameterIdx);
		
		if(targetSQLType == Types.ARRAY || targetSQLType == Types.STRUCT || targetSQLType == Types.OTHER) {
			targetSQLType = Types.NULL;
		}
		
		if(paramType == null || targetSQLType != getSQLType(paramType)) {
			
			paramType = SQLTypeMetaData.getType(targetSQLType, connection.getRegistry());
			
			parameterTypes.set(parameterIdx, paramType);
			
			parsed = false;
		}
		
		parameterValues.set(parameterIdx, val);
	}

	void internalClose() throws SQLException {

		super.internalClose();
		
		parameterTypes = null;
		parameterValues = null;
	}
	
	void parseIfNeeded() throws SQLException {
		
		if(!parsed) {
			
			if(name != null) {
				connection.execute(connection.getProtocol().createClose(ServerObjectType.Statement, name), false);
			}
			
			PrepareCommand prep = connection.getProtocol().createPrepare(name, sqlText.toString(), parameterTypes);
			
			warningChain = connection.execute(prep, true);
			
			parameterTypes = prep.getDescribedParameterTypes();
			resultFields = prep.getDescribedResultFields();
			
			parsed = true;
		}
				
	}

	@Override
	public boolean execute() throws SQLException {
		
		parseIfNeeded();
		
		for(int c=0, sz=parameterTypes.size(); c < sz; ++c) {

			Type parameterType = parameterTypes.get(c);
			Object parameterValue = parameterValues.get(c);
			
			if(parameterValue != null) {

				Class<?> targetType = mapSetType(parameterType);
				
				try {
					parameterValue = coerce(parameterValue, parameterType, targetType, Collections.<String,Class<?>>emptyMap(), TimeZone.getDefault(), connection);
				}
				catch(SQLException coercionException) {
					throw new SQLException("Error converting parameter " + c, coercionException);
				}
			}
			
			parameterValues.set(c, parameterValue);
		}
				
		
		boolean res = super.executeStatement(name, parameterTypes, parameterValues);
		
		if(wantsGeneratedKeys) {
			generatedKeysResultSet = getResultSet();
		}

		return res;
	}

	@Override
	public PGResultSet executeQuery() throws SQLException {

		execute();

		return getResultSet();
	}

	@Override
	public int executeUpdate() throws SQLException {

		execute();

		return getUpdateCount();
	}

	@Override
	public void addBatch() throws SQLException {
		checkClosed();
		
		if(batchParameterTypes == null) {
			batchParameterTypes = new ArrayList<>();
		}
		
		if(batchParameterValues == null) {
			batchParameterValues = new ArrayList<>();
		}
		
		batchParameterTypes.add(new ArrayList<>(parameterTypes));
		batchParameterValues.add(new ArrayList<>(parameterValues));
	}

	@Override
	public void clearBatch() throws SQLException {
		checkClosed();
		
		batchParameterValues = null;
	}

	@Override
	public int[] executeBatch() throws SQLException {
		checkClosed();
		
		try {
			
			if(batchParameterValues == null || batchParameterValues.isEmpty()) {
				return new int[0];
			}
			
			int[] counts = new int[batchParameterValues.size()];
			Arrays.fill(counts, SUCCESS_NO_INFO);
			
			List<Object[]> generatedKeys = new ArrayList<>();
			
			BindExecCommand command = connection.getProtocol().createBindExec(null, null, parameterTypes, Collections.emptyList(), resultFields, Object[].class);
	
			List<Type> lastParameterTypes = null;
			List<ResultField> lastResultFields = null;

			for(int c=0, sz=batchParameterValues.size(); c < sz; ++c) {
				
				List<Type> parameterTypes = mergeTypes(batchParameterTypes.get(c), lastParameterTypes);
				
				if(lastParameterTypes == null || lastParameterTypes.equals(parameterTypes) == false) {
				
					PrepareCommand prep = connection.getProtocol().createPrepare(null, sqlText, parameterTypes);
					
					connection.execute(prep, true);
					
					parameterTypes = prep.getDescribedParameterTypes();
					lastParameterTypes = parameterTypes;
					lastResultFields = prep.getDescribedResultFields();
				}
				
				List<Object> parameterValues = batchParameterValues.get(c);
				
				command.setParameterTypes(parameterTypes);
				command.setParameterValues(parameterValues);
				
				SQLWarning warnings = connection.execute(command, true);
				
				warningChain = chainWarnings(warningChain, warnings);
				
				List<QueryCommand.ResultBatch> resultBatches = command.getResultBatches();
				if(resultBatches.size() != 1) {
					throw new BatchUpdateException(counts);
				}
			
				QueryCommand.ResultBatch resultBatch = resultBatches.get(0);
				if(resultBatch.rowsAffected == null) {
					throw new BatchUpdateException(counts);
				}
				
				if(wantsGeneratedKeys) {
					generatedKeys.add((Object[])resultBatch.results.get(0));
				}
				
				counts[c] = (int)(long)resultBatch.rowsAffected;
			}
			
			generatedKeysResultSet = createResultSet(lastResultFields, generatedKeys);

			return counts;
			
		}
		finally {
			batchParameterTypes = null;
			batchParameterValues = null;
		}

	}

	private List<Type> mergeTypes(List<Type> list, List<Type> defaultTypes) {
		
		if(defaultTypes == null)
			return list;
		
		for(int c=0, sz=list.size(); c < sz; ++c) {
			
			Type type = list.get(c);
			if(type == null)
				type = defaultTypes.get(c);
			
			list.set(c, type);
		}
		
		return list;
	}

	@Override
	public void clearParameters() throws SQLException {
		checkClosed();

		for (int c = 0; c < parameterValues.size(); ++c) {
		
			parameterValues.set(c, null);
		
		}
		
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		checkClosed();
		
		parseIfNeeded();
		
		return new PGParameterMetaData(parameterTypes, connection.getTypeMap());
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		checkClosed();

		parseIfNeeded();
		
		return new PGResultSetMetaData(connection, resultFields, connection.getTypeMap());
	}

	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		set(parameterIndex, null);
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setByte(int parameterIndex, byte x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setBytes(int parameterIndex, byte[] x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {		
		setDate(parameterIndex, x, Calendar.getInstance());
	}

	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {		
		setTime(parameterIndex, x, Calendar.getInstance());
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {		
		setTimestamp(parameterIndex, x, Calendar.getInstance());
	}

	@Override
	public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
		
		TimeZone zone = cal.getTimeZone();
		
		set(parameterIndex, Instants.fromDate(x, zone));
	}

	@Override
	public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {

		
		TimeZone zone = cal.getTimeZone();
		
		set(parameterIndex, Instants.fromTime(x, zone));
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
		checkClosed();
		checkParameterIndex(parameterIndex);

		TimeZone zone = cal.getTimeZone();
		
		set(parameterIndex, Instants.fromTimestamp(x, zone));
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {

		_setBinaryStream(parameterIndex, x, (long) -1);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		
		if(length < 0) {
			throw new SQLException("Invalid length");
		}
		
		if(x != null) {

			x = ByteStreams.limit(x, length);
		}
		else if(length != 0) {
			throw new SQLException("Invalid length");
		}		

		_setBinaryStream(parameterIndex, x, length);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		
		if(length < 0) {
			throw new SQLException("Invalid length");
		}
		
		if(x != null) {

			x = ByteStreams.limit(x, length);
		}
		else if(length != 0) {
			throw new SQLException("Invalid length");
		}

		_setBinaryStream(parameterIndex, x, length);
	}
	
	public void _setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		
		if(x == null) {
			
			set(parameterIndex, null);
		}
		else {
			
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				
				long read = ByteStreams.copy(x, out);
				
				if(length != -1 && read != length) {
					throw new SQLException("Not enough data in stream");
				}
				
			}
			catch(IOException e) {
				throw new SQLException(e);
			}
	
			set(parameterIndex, out.toByteArray());
			
		}
		
	}

	@Override
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {

		InputStreamReader reader = new InputStreamReader(x, UTF_8);
		
		setCharacterStream(parameterIndex, reader, length);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
		setAsciiStream(parameterIndex, x, (long) -1);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		setAsciiStream(parameterIndex, x, (long) length);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {

		InputStreamReader reader = new InputStreamReader(x, US_ASCII);
		
		setCharacterStream(parameterIndex, reader, length);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
		setCharacterStream(parameterIndex, reader, (long) -1);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		setCharacterStream(parameterIndex, reader, (long) length);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {

		StringWriter writer = new StringWriter();
		try {
			CharStreams.copy(reader, writer);
		}
		catch(IOException e) {
			throw new SQLException(e);
		}
		
		set(parameterIndex, writer.toString());
	}

	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		checkClosed();
		
		setObject(parameterIndex, x, targetSqlType, 0);
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		checkClosed();
		checkParameterIndex(parameterIndex);

		set(parameterIndex, unwrap(x), targetSqlType);
	}

	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {		
		set(parameterIndex, unwrap(x)); 
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
		setBlob(parameterIndex, ByteStreams.limit(inputStream, length));
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		
		Blob blob = connection.createBlob();
		
		try {
			ByteStreams.copy(inputStream, blob.setBinaryStream(0));
		}
		catch(IOException e) {
			throw new SQLException(e);
		}

		set(parameterIndex, blob);
	}
	
	@Override
	public void setArray(int parameterIndex, Array x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
		set(parameterIndex, null);
	}

	@Override
	public void setURL(int parameterIndex, URL x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
		checkClosed();
		
		if(xmlObject instanceof PGSQLXML == false) {
			throw new SQLException("SQLXML object not created by driver");
		}
		
		PGSQLXML sqlXml = (PGSQLXML) xmlObject;
		
		set(parameterIndex, sqlXml.getData());
	}

	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNString(int parameterIndex, String value) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setClob(int parameterIndex, Clob x) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNClob(int parameterIndex, NClob value) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setClob(int parameterIndex, Reader reader) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public int executeUpdate(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	private Object unwrap(Object x) throws SQLException {
		
		if(x instanceof Blob) {
			return unwrap((Blob)x);
		}
		
		return x;
	}
	
	private PGBlob unwrap(Blob x) throws SQLException {
		
		if(x instanceof PGBlob)
			return (PGBlob) x;

		InputStream in = x.getBinaryStream();
		if(in instanceof BlobInputStream) {
			return new PGBlob(connection, ((BlobInputStream) in).lo.oid);
		}
		
		PGBlob nx = (PGBlob)connection.createBlob();
		OutputStream out = nx.setBinaryStream(1);
		
		try {
			
			ByteStreams.copy(in, out);
			
			in.close();
			out.close();
		}
		catch (IOException e) {
			throw new SQLException(e);
		}
		
		return nx;
	}

}
