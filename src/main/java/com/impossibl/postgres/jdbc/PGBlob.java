package com.impossibl.postgres.jdbc;

import static com.impossibl.postgres.jdbc.Exceptions.CLOSED_BLOB;
import static com.impossibl.postgres.jdbc.Exceptions.ILLEGAL_ARGUMENT;

import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.common.io.ByteStreams;

public class PGBlob implements Blob {
	
  private class LOByteIterator
  {
      private static final int MAX_BUFFER_SIZE = 8096;
      private byte buffer[] = {};
      private int idx = 0;

      LOByteIterator(long start) throws SQLException {
          lo.lseek(start, LargeObject.SEEK_SET);
      }

      boolean hasNext() throws SQLException
      {
          boolean result = false;
          if(idx < buffer.length) {
              result = true;
          }
          else {
              buffer = lo.read(MAX_BUFFER_SIZE);
              idx = 0;
              result = (buffer.length > 0);
          }
          return result;
      }

      private byte next()
      {
          return buffer[idx++];
      }
  }	


  LargeObject lo;
	List<LargeObject> streamLos;

	PGBlob(PGConnection connection, int oid) throws SQLException {
		
		if(connection.getAutoCommit() == true) {
			throw new SQLException("Blobs require connection to be in manual-commit mode... setAutoCommit(false)");
		}
		
		lo = LargeObject.open(connection, oid);
		streamLos = new ArrayList<>();
	}
	
	private void checkClosed() throws SQLException {
		if(lo == null) {
			throw CLOSED_BLOB;
		}
	}

	private void checkPosition(long pos) throws SQLException {
		if(pos < 1) {
			throw ILLEGAL_ARGUMENT;
		}
	}

	@Override
	public long length() throws SQLException {
		checkClosed();

		long cur = lo.tell();
		lo.lseek(0, LargeObject.SEEK_END);
		long len = lo.tell();
		lo.lseek(cur, LargeObject.SEEK_SET);
		return len;
	}

	@Override
	public byte[] getBytes(long pos, int length) throws SQLException {
		checkClosed();
		checkPosition(pos);

		lo.lseek(pos-1, LargeObject.SEEK_SET);
		return lo.read(length);
	}

	@Override
	public InputStream getBinaryStream() throws SQLException {
		checkClosed();

		LargeObject streamLo = lo.dup();
		streamLos.add(streamLo);
		return new BlobInputStream(streamLo);
	}

	@Override
	public InputStream getBinaryStream(long pos, long length) throws SQLException {
		checkClosed();
		checkPosition(pos);

		LargeObject streamLo = lo.dup();
		streamLos.add(streamLo);
		streamLo.lseek(pos-1, LargeObject.SEEK_SET);
		return ByteStreams.limit(new BlobInputStream(streamLo), length); 
	}

	@Override
	public long position(byte[] pattern, long start) throws SQLException {
		checkClosed();
		checkPosition(start);

		LOByteIterator iter = new LOByteIterator(start-1);
		long curPos=start, matchStartPos=0;
		int patternIdx=0;
		
		while(iter.hasNext()) {
			
			byte b = iter.next();
			
			if(b == pattern[patternIdx]) {
				
				if(patternIdx == 0) {
					matchStartPos = curPos;
				}
				
				patternIdx++;
				
				if(patternIdx == pattern.length) {
					return matchStartPos;
				}
			}
			else {
				patternIdx = 0;
			}
			
			curPos++;
		}

		return -1;
	}
	
	@Override
	public long position(Blob pattern, long start) throws SQLException {
		checkClosed();

		return position(pattern.getBytes(1, (int)pattern.length()), start);
	}

	@Override
	public int setBytes(long pos, byte[] bytes) throws SQLException {
		
		return setBytes(pos, bytes, 0, bytes.length);
	}

	@Override
	public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
		checkClosed();
		checkPosition(pos);

		lo.lseek(pos-1, LargeObject.SEEK_SET);
		return lo.write(bytes, offset, len);
	}

	@Override
	public OutputStream setBinaryStream(long pos) throws SQLException {
		checkClosed();
		checkPosition(pos);

		LargeObject streamLo = lo.dup();
		streamLos.add(streamLo);
		streamLo.lseek(pos-1, LargeObject.SEEK_SET);
		return new BlobOutputStream(this, streamLo);
	}

	@Override
	public void truncate(long len) throws SQLException {
		checkClosed();

		lo.truncate(len);
	}

	@Override
	public void free() throws SQLException {

		if(lo == null)
			return;

		lo.close();
		lo = null;
		
		for(LargeObject streamLo : streamLos) {
			streamLo.close();
		}
		streamLos.clear();
	}
	
	void removeStream(LargeObject lo) {		
		streamLos.remove(lo);
	}

}
