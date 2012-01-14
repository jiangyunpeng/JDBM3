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

package net.kotek.jdbm;

import java.io.IOError;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Iterator;

/**
 * A DB wrapping and caching another DB.
 *
 * @author Jan Kotek
 * @author Alex Boisvert
 * @author Cees de Groot
 *
 * TODO add 'cache miss' statistics
 */
class DBCache
        extends DBAbstract {

    /**
     * Wrapped DB
     */
    protected DBStore _db;


    /**
     * Cached object hashtable
     */
    protected LongHashMap<CacheEntry> _hash;

    /**
     * If Soft Cache is enabled, this contains softly referenced clean entries.
     * If entry became dirty, it is moved to _hash with limited size.
     * This map is accessed from SoftCache Disposer thread, so all access must be
     * synchronized
     */
    protected LongHashMap<ReferenceCacheEntry> _softHash;

    /**
     * Reference queue used to collect Soft Cache entries
     */
    protected ReferenceQueue<ReferenceCacheEntry> _refQueue;


    /**
     * Maximum number of objects in the cache.
     */
    protected int _max;

    /**
     * True if enable second level soft cache
     */
    final protected boolean _enableReferenceCache;

    /**
     * True if SoftReference should be used, otherwise use WeakReference
     */
    final protected boolean _useSoftReference;

    /**
     * Thread in which Soft Cache references are disposed
     */
    protected Thread _softRefThread;

    protected static int threadCounter = 0;

    /**
     * Beginning of linked-list of cache elements.  First entry is element
     * which has been used least recently.
     */
    protected CacheEntry _first;

    /**
     * End of linked-list of cache elements.  Last entry is element
     * which has been used most recently.
     */
    protected CacheEntry _last;


    /**
     * Construct a CacheRecordManager wrapping another DB and
     * using a given cache policy.
     *
     * @param db                   Wrapped DB
     * @param maxRecords           maximal number of records in MRU cache
     * @param enableReferenceCache if cache using WeakReference or SoftReference should be enabled
     * @param useSoftReference     if reference cache is enabled, decides beetween Soft or Weak reference
     */
    public DBCache(DBStore db, int maxRecords, boolean enableReferenceCache, boolean useSoftReference) {
        if (db == null) {
            throw new IllegalArgumentException("Argument 'db' is null");
        }
        _hash = new LongHashMap<CacheEntry>(maxRecords);
        _db = db;
        _max = maxRecords;
        _enableReferenceCache = enableReferenceCache;
        _useSoftReference = useSoftReference;

        if (enableReferenceCache) {
            _softHash = new LongHashMap<ReferenceCacheEntry>();
            _refQueue = new ReferenceQueue<ReferenceCacheEntry>();
            _softRefThread = new Thread(
                    new SoftRunnable(this, _refQueue),
                    "JDBM Soft Cache Disposer " + (threadCounter++));
            _softRefThread.setDaemon(true);
            _softRefThread.start();
        }

    }


    public synchronized <A> long insert(A obj, Serializer<A> serializer)
            throws IOException {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }
        
        if(_db.needsAutoCommit())
            commit();

        long recid = _db.insert(obj, serializer);


        if(_enableReferenceCache) synchronized(_softHash) {
            if (_useSoftReference)
                _softHash.put(recid, new SoftCacheEntry(recid, obj, _refQueue));
            else
                _softHash.put(recid, new WeakCacheEntry(recid, obj, _refQueue));
        }else {
        	cachePut(  recid , obj, serializer, false );
        }
        return recid;
    }

    public synchronized <A> A fetch(long recid, Serializer<A> serializer, boolean disableCache) throws IOException {
        if (disableCache)
            return _db.fetch(recid, serializer, disableCache);
        else
            return fetch(recid, serializer);
    }


    public synchronized void delete(long recid)
            throws IOException {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        if(_db.needsAutoCommit())
            commit();

        _db.delete(recid);
        CacheEntry entry = _hash.get(recid);
        if (entry != null) {
            removeEntry(entry);
            _hash.remove(entry._recid);
        }
        if (_enableReferenceCache) synchronized (_softHash) {
            ReferenceCacheEntry e = _softHash.remove(recid);
            if (e != null) {
                e.clear();
            }
        }

    }

    public synchronized <A> void update(long recid, A obj,
                                        Serializer<A> serializer)
            throws IOException {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        if(_db.needsAutoCommit())
            commit();

        if (_enableReferenceCache) synchronized (_softHash) {
            //soft cache can not contain dirty objects
            ReferenceCacheEntry e = _softHash.remove(recid);
            if (e != null) {
                e.clear();
            }
        }
        CacheEntry entry = cacheGet(recid);
        if (entry != null) {
            // reuse existing cache entry
            entry._obj = obj;
            entry._serializer = serializer;
            entry._isDirty = true;
        } else {
            cachePut(recid, obj, serializer, true);
        }
    }


    public synchronized <A> A fetch(long recid, Serializer<A> serializer)
            throws IOException {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        if (_enableReferenceCache) synchronized (_softHash) {
            ReferenceCacheEntry e = _softHash.get(recid);
            if (e != null) {
                Object a = e.get();
                if (a != null) {
                    return (A) a;
                }
            }
        }

        CacheEntry entry = cacheGet(recid);
        if (entry == null) {
            A value = _db.fetch(recid, serializer);
            if (!_enableReferenceCache)
                cachePut(recid, value, serializer, false);
            else { //put record into soft cache
                synchronized (_softHash) {
                    if (_useSoftReference)
                        _softHash.put(recid, new SoftCacheEntry(recid, value, _refQueue));
                    else
                        _softHash.put(recid, new WeakCacheEntry(recid, value, _refQueue));
                }
            }
            return value;
        } else {
            return (A) entry._obj;
        }
    }


    public synchronized void close() {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        updateCacheEntries();
        _db.close();
        _db = null;
        _hash = null;
        _softHash = null;
        if (_enableReferenceCache)
            _softRefThread.interrupt();
    }


    public synchronized void commit() {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }
        updateCacheEntries();
        _db.commit();
    }

    public synchronized void rollback() {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        _db.rollback();

        // discard all cache entries since we don't know which entries
        // where part of the transaction
        _hash.clear();
        if (_enableReferenceCache) synchronized (_softHash) {
            Iterator<ReferenceCacheEntry> iter = _softHash.valuesIterator();
            while (iter.hasNext()) {
                ReferenceCacheEntry e = iter.next();
                e.clear();
            }
            _softHash.clear();
        }
        _first = null;
        _last = null;
    }


    public synchronized long getNamedObject(String name)
            throws IOException {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        return _db.getNamedObject(name);
    }


    public synchronized void setNamedObject(String name, long recid)
            throws IOException {
        if (_db == null) {
            throw new IllegalStateException("DB has been closed");
        }

        _db.setNamedObject(name, recid);
    }

    public Serializer defaultSerializer() {
        return _db.defaultSerializer();
    }

    public String calculateStatistics() {
        return _db.calculateStatistics();
    }


    /**
     * Update all dirty cache objects to the underlying DB.
     */
    protected void updateCacheEntries() {
        try {
            Iterator<CacheEntry> iter = _hash.valuesIterator();
            while (iter.hasNext()) {
                CacheEntry entry = iter.next();
                if (entry._isDirty) {
                    _db.update(entry._recid, entry._obj, entry._serializer);
                    entry._isDirty = false;
                }
            }
        } catch (IOException e) {
            throw new IOError(e);
        }

    }


    /**
     * Obtain an object in the cache
     */
    protected CacheEntry cacheGet(long key) {
        CacheEntry entry = _hash.get(key);
        if (!_enableReferenceCache && entry != null && _last != entry) {
            //touch entry
            removeEntry(entry);
            addEntry(entry);
        }
        return entry;
    }


    /**
     * Place an object in the cache.
     *
     * @throws IOException
     */
    protected void cachePut(long recid, Object value, Serializer serializer, boolean dirty) throws IOException {
        CacheEntry entry = _hash.get(recid);
        if (entry != null) {
            entry._obj = value;
            entry._serializer = serializer;
            //touch entry
            if (_last != entry) {
                removeEntry(entry);
                addEntry(entry);
            }
        } else {

            if (_hash.size() == _max) {
                // purge and recycle entry
                entry = purgeEntry();
                entry._recid = recid;
                entry._obj = value;
                entry._isDirty = dirty;
                entry._serializer = serializer;
            } else {
                entry = new CacheEntry(recid, value, serializer, dirty);
            }
            addEntry(entry);
            _hash.put(entry._recid, entry);
        }
    }

    /**
     * Add a CacheEntry.  Entry goes at the end of the list.
     */
    protected void addEntry(CacheEntry entry) {
        if (_first == null) {
            _first = entry;
            _last = entry;
        } else {
            _last._next = entry;
            entry._previous = _last;
            _last = entry;
        }
    }


    /**
     * Remove a CacheEntry from linked list
     */
    protected void removeEntry(CacheEntry entry) {
        if (entry == _first) {
            _first = entry._next;
        }
        if (_last == entry) {
            _last = entry._previous;
        }
        CacheEntry previous = entry._previous;
        CacheEntry next = entry._next;
        if (previous != null) {
            previous._next = next;
        }
        if (next != null) {
            next._previous = previous;
        }
        entry._previous = null;
        entry._next = null;
    }

    /**
     * Purge least recently used object from the cache
     *
     * @return recyclable CacheEntry
     */
    protected CacheEntry purgeEntry() {
        CacheEntry entry = _first;
        if (entry == null)
            return new CacheEntry(-1, null, null, false);

        if (entry._isDirty) try {
            _db.update(entry._recid, entry._obj, entry._serializer);
        } catch (IOException e) {
            throw new IOError(e);
        }

        removeEntry(entry);
        _hash.remove(entry._recid);


        entry._obj = null;
        entry._serializer = null;
        entry._isDirty = false;
        return entry;
    }


    @SuppressWarnings("unchecked")
    static final class CacheEntry {

        protected long _recid;
        protected Object _obj;

        protected Serializer _serializer;
        protected boolean _isDirty;

        protected CacheEntry _previous;
        protected CacheEntry _next;


        CacheEntry(long recid, Object obj, Serializer serializer, boolean isDirty) {
            _recid = recid;
            _obj = obj;
            _serializer = serializer;
            _isDirty = isDirty;
        }

    }

    interface ReferenceCacheEntry {
        long getRecid();

        void clear();

        Object get();
    }

    @SuppressWarnings("unchecked")
    static final class SoftCacheEntry extends SoftReference implements ReferenceCacheEntry {
        protected final long _recid;

        public long getRecid() {
            return _recid;
        }

        SoftCacheEntry(long recid, Object obj, ReferenceQueue queue) {
            super(obj, queue);
            _recid = recid;
        }
    }

    @SuppressWarnings("unchecked")
    static final class WeakCacheEntry extends WeakReference implements ReferenceCacheEntry {
        protected final long _recid;

        public long getRecid() {
            return _recid;
        }

        WeakCacheEntry(long recid, Object obj, ReferenceQueue queue) {
            super(obj, queue);
            _recid = recid;
        }
    }


    /**
     * Runs in separate thread and cleans SoftCache.
     * Runnable auto exists when CacheRecordManager is GCed
     *
     * @author Jan Kotek
     */
    static final class SoftRunnable implements Runnable {

        private ReferenceQueue<ReferenceCacheEntry> entryQueue;
        private WeakReference<DBCache> db2;

        public SoftRunnable(DBCache db,
                            ReferenceQueue<ReferenceCacheEntry> entryQueue) {
            this.db2 = new WeakReference<DBCache>(db);
            this.entryQueue = entryQueue;
        }

        public void run() {
            while (true) try {

                //collect next item from cache,
                //limit 10000 ms is to keep periodically checking if db was GCed
                SoftCacheEntry e = (SoftCacheEntry) entryQueue.remove(10000);

                //check if  db was GCed, cancel in that case
                DBCache db = db2.get();
                if (db == null)
                    return;
                if (e != null) {
                    synchronized (db._softHash) {
                        while (e != null) {
                            db._softHash.remove(e._recid);
                            e = (SoftCacheEntry) entryQueue.poll();
                        }
                    }
                }

            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                //this thread must keep spinning,
                //otherwise SoftCacheEntries would not be disposed
                e.printStackTrace();
            }
        }

    }


    public void clearCache() {

        // discard all cache entries since we don't know which entries
        // where part of the transaction
        while (_hash.size() > 0)
            purgeEntry();

        if (_enableReferenceCache) synchronized (_softHash) {
            Iterator<ReferenceCacheEntry> iter = _softHash.valuesIterator();
            while (iter.hasNext()) {
                ReferenceCacheEntry e = iter.next();
                e.clear();
            }
            _softHash.clear();
        }
        _first = null;
        _last = null;

    }


    public void defrag() {
        commit();
        _db.defrag();
    }


}
