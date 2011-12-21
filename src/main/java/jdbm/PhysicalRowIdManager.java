/*******************************************************************************
 * Copyright 2010 Cees De Groot, Alex Boisvert, Jan Kotek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package jdbm;

import java.io.DataOutput;
import java.io.IOException;
import static jdbm.Storage.*;

/**
 * This class manages physical row ids, and their data.
 */
final class PhysicalRowIdManager {

	// The file we're talking to and the associated page manager.
	final private RecordFile file;
	final private PageManager pageman;
	final private FreePhysicalRowIdPageManager freeman;
	static final private short DATA_PER_PAGE =  (short) (BLOCK_SIZE - DataPage.O_DATA);
    //caches offset after last allocation. So we dont have to iterate throw page every allocation
    private long cachedLastAllocatedRecordPage = Long.MIN_VALUE;
    private short cachedLastAllocatedRecordOffset = Short.MIN_VALUE;

    /**
	 * Creates a new rowid manager using the indicated record file. and page manager.
	 */
	PhysicalRowIdManager(RecordFile file, PageManager pageManager, FreePhysicalRowIdPageManager freeman) throws IOException {
		this.file = file;
		this.pageman = pageManager;
		this.freeman = freeman;

	}

	/**
	 * Inserts a new record. Returns the new physical rowid.
	 */
	long insert(byte[] data, int start, int length) throws IOException {
		if (length < 1)
			throw new IllegalArgumentException("Lenght is <1");
		if (start < 0)
			throw new IllegalArgumentException("negative start");

		long retval = alloc(length);
		write(retval, data, start, length);
		return retval;
	}

	/**
	 * Updates an existing record. Returns the possibly changed physical rowid.
	 */
	long update(long rowid, byte[] data, int start, int length) throws IOException {
		// fetch the record header
		BlockIo block = file.get(Location.getBlock(rowid));
		short head = Location.getOffset(rowid);
		int availSize = RecordHeader.getAvailableSize(block, head);
		if (length > availSize || 
			//difference between free and available space can be only 64KB. 
			//if bigger, need to realocate and free block	
			availSize - length > RecordHeader.MAX_SIZE_SPACE	
		) {
			// not enough space - we need to copy to a new rowid.
			file.release(block);
			free(rowid);
			rowid = alloc(length);
		} else {
			file.release(block);
		}

		// 'nuff space, write it in and return the rowid.
		write(rowid, data, start, length);
		return rowid;
	} 

	/**
	 * Deletes a record.
	 */
	void delete(long rowid) throws IOException {
		free(rowid);
	}

	void fetch(DataOutput out, long rowid) throws IOException {
		// fetch the record header
		long current = Location.getBlock(rowid);
		BlockIo block = file.get(current);
		short head = Location.getOffset(rowid);

		// allocate a return buffer
		// byte[] retval = new byte[ head.getCurrentSize() ];
		final int size = RecordHeader.getCurrentSize(block,head);
		if (size == 0) {
			file.release(current, false);
			return;
		}

		// copy bytes in
		int leftToRead = size;
		short dataOffset = (short) (Location.getOffset(rowid) + RecordHeader.SIZE);
		while (leftToRead > 0) {
			// copy current page's data to return buffer
			int toCopy = BLOCK_SIZE - dataOffset;
			if (leftToRead < toCopy) {
				toCopy = leftToRead;
			}
			byte[] blockData = block.getData();
			int finish = dataOffset + toCopy;
			out.write(blockData, dataOffset, finish - dataOffset);

			// Go to the next block
			leftToRead -= toCopy;
			// out.flush();
			file.release(block);

			if (leftToRead > 0) {
                current = pageman.getNext(current);
				block = file.get(current);
				dataOffset = DataPage.O_DATA;
			}

		}

		// return retval;
	}

	/**
	 * Allocate a new rowid with the indicated size.
	 */
	private long alloc(int size) throws IOException {
		size = RecordHeader.roundAvailableSize(size);
		long retval = freeman.get(size);
		if (retval == 0) {
			retval = allocNew(size, pageman.getLast(Magic.USED_PAGE));
		}
		return retval;
	}

	/**
	 * Allocates a new rowid. The second parameter is there to allow for a recursive call - it indicates where the
	 * search should start.
	 */
	private long allocNew(int size, long start) throws IOException {
		BlockIo curBlock;
		DataPage curPage;
		if (start == 0 ||
                //last page was completely filled?
                cachedLastAllocatedRecordPage == start && cachedLastAllocatedRecordOffset == BLOCK_SIZE
                ) {
			// we need to create a new page.
			start = pageman.allocate(Magic.USED_PAGE);
			curBlock = file.get(start);
			curPage = DataPage.getDataPageView(curBlock,BLOCK_SIZE);
			curPage.setFirst(DataPage.O_DATA);
            cachedLastAllocatedRecordOffset = DataPage.O_DATA;
            cachedLastAllocatedRecordPage = curBlock.getBlockId();
			RecordHeader.setAvailableSize(curBlock, DataPage.O_DATA, 0);
			RecordHeader.setCurrentSize(curBlock, DataPage.O_DATA, 0);

		} else {
			curBlock = file.get(start);
			curPage = DataPage.getDataPageView(curBlock,BLOCK_SIZE);
		}

		// follow the rowids on this page to get to the last one. We don't
		// fall off, because this is the last page, remember?
		short pos = curPage.getFirst();
		if (pos == 0) {
			// page is exactly filled by the last block of a record
			file.release(curBlock);
			return allocNew(size, 0);
		}

		short hdr = pos;

        if(cachedLastAllocatedRecordPage != curBlock.getBlockId()){
            //position was not cached, have to find it again
            int availSize = RecordHeader.getAvailableSize(curBlock, hdr);
		    while (availSize != 0 && pos < BLOCK_SIZE) {
			    pos += availSize + RecordHeader.SIZE;
			    if (pos == BLOCK_SIZE) {
				    // Again, a filled page.
				    file.release(curBlock);
				    return allocNew(size, 0);
			    }
			    hdr = pos;
                availSize = RecordHeader.getAvailableSize(curBlock, hdr);
		    }
        }else{
            hdr = cachedLastAllocatedRecordOffset;
            pos = cachedLastAllocatedRecordOffset;
        }

		if (pos == RecordHeader.SIZE) {
			// the last record exactly filled the page. Restart forcing
			// a new page.
			file.release(curBlock);
		}

		// we have the position, now tack on extra pages until we've got
		// enough space.
		long retval = Location.toLong(start, pos);
		int freeHere = BLOCK_SIZE - pos - RecordHeader.SIZE;
		if (freeHere < size) {
			// check whether the last page would have only a small bit left.
			// if yes, increase the allocation. A small bit is a record
			// header plus 16 bytes.
			int lastSize = (size - freeHere) % DATA_PER_PAGE;
			if ((DATA_PER_PAGE - lastSize) < (RecordHeader.SIZE + 16)) {
				size += (DATA_PER_PAGE - lastSize);
				size = RecordHeader.roundAvailableSize(size);
			}

			// write out the header now so we don't have to come back.
			RecordHeader.setAvailableSize(curBlock, hdr, size);
			file.release(start, true);

			int neededLeft = size - freeHere;
			// Refactor these two blocks!
			while (neededLeft >= DATA_PER_PAGE) {
				start = pageman.allocate(Magic.USED_PAGE);
				curBlock = file.get(start);
				curPage = DataPage.getDataPageView(curBlock, BLOCK_SIZE);
				curPage.setFirst((short) 0); // no rowids, just data
				file.release(start, true);
				neededLeft -= DATA_PER_PAGE;
			}
			if (neededLeft > 0) {
				// done with whole chunks, allocate last fragment.
				start = pageman.allocate(Magic.USED_PAGE);
				curBlock = file.get(start);
				curPage = DataPage.getDataPageView(curBlock, BLOCK_SIZE);
				curPage.setFirst((short) (DataPage.O_DATA + neededLeft));
				file.release(start, true);
                cachedLastAllocatedRecordOffset = (short) (DataPage.O_DATA + neededLeft);
                cachedLastAllocatedRecordPage = curBlock.getBlockId();

			}
		} else {
			// just update the current page. If there's less than 16 bytes
			// left, we increase the allocation (16 bytes is an arbitrary
			// number).
			if (freeHere - size <= (16 + RecordHeader.SIZE)) {
				size = freeHere;
			}
			RecordHeader.setAvailableSize(curBlock, hdr, size);
			file.release(start, true);
            cachedLastAllocatedRecordOffset = (short) (hdr+ RecordHeader.SIZE + size);
            cachedLastAllocatedRecordPage = curBlock.getBlockId();

		}
		return retval;

	}

	private void free(long id) throws IOException {
		// get the rowid, and write a zero current size into it.
		BlockIo curBlock = file.get(Location.getBlock(id));

		RecordHeader.setCurrentSize(curBlock, Location.getOffset(id), 0);
		file.release(Location.getBlock(id), true);

		// write the rowid to the free list
		freeman.put(id, RecordHeader.getAvailableSize(curBlock, Location.getOffset(id)));	
	}

	/**
	 * Writes out data to a rowid. Assumes that any resizing has been done.
	 */
	private void write(long rowid, byte[] data, int start, int length) throws IOException {
		long current =  Location.getBlock(rowid);
		BlockIo block = file.get(current);
		short hdr = Location.getOffset(rowid);
		RecordHeader.setCurrentSize(block, hdr, length);
		if (length == 0) {
			file.release(current, true);
			return;
		}

		// copy bytes in
		int offsetInBuffer = start;
		int leftToWrite = length;
		short dataOffset = (short) (Location.getOffset(rowid) + RecordHeader.SIZE);
		while (leftToWrite > 0) {
			// copy current page's data to return buffer
			int toCopy = BLOCK_SIZE - dataOffset;

			if (leftToWrite < toCopy) {
				toCopy = leftToWrite;
			}
			System.arraycopy(data, offsetInBuffer, block.getData(), dataOffset, toCopy);

			// Go to the next block
			leftToWrite -= toCopy;
			offsetInBuffer += toCopy;

			file.release(current, true);

			if (leftToWrite > 0) {
                current = pageman.getNext(current);
				block = file.get(current);
				dataOffset = DataPage.O_DATA;
			}
		}
	}

    void roolback() throws IOException {
   		cachedLastAllocatedRecordPage = Long.MIN_VALUE;
        cachedLastAllocatedRecordOffset = Short.MIN_VALUE;
   	}


	void commit() throws IOException {
		freeman.commit();		
	}
}
