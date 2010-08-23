/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.storage.berkeleydb;

import org.apache.commons.io.FileUtils;

import com.sleepycat.bind.serial.ClassCatalog;
import com.sleepycat.bind.serial.StoredClassCatalog;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.beans.object.ServerDBBackup;
import com.tc.management.beans.object.ServerDBBackupMBean;
import com.tc.objectserver.persistence.db.DBException;
import com.tc.objectserver.persistence.db.DatabaseNotOpenException;
import com.tc.objectserver.persistence.db.DatabaseOpenException;
import com.tc.objectserver.persistence.db.TCDatabaseException;
import com.tc.objectserver.storage.api.DBEnvironment;
import com.tc.objectserver.storage.api.PersistenceTransactionProvider;
import com.tc.objectserver.storage.api.TCBytesToBytesDatabase;
import com.tc.objectserver.storage.api.TCIntToBytesDatabase;
import com.tc.objectserver.storage.api.TCLongDatabase;
import com.tc.objectserver.storage.api.TCLongToStringDatabase;
import com.tc.objectserver.storage.api.TCMapsDatabase;
import com.tc.objectserver.storage.api.TCObjectDatabase;
import com.tc.objectserver.storage.api.TCRootDatabase;
import com.tc.objectserver.storage.api.TCStringToStringDatabase;
import com.tc.util.concurrent.ThreadUtil;
import com.tc.util.sequence.MutableSequence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class BerkeleyDBEnvironment implements DBEnvironment {

  private static final TCLogger      clogger                     = CustomerLogging.getDSOGenericLogger();
  private static final TCLogger      logger                      = TCLogging.getLogger(BerkeleyDBEnvironment.class);

  private static final Object        CONTROL_LOCK                = new Object();

  private static final DatabaseEntry CLEAN_FLAG_KEY              = new DatabaseEntry(new byte[] { 1 });
  private static final byte          IS_CLEAN                    = 1;
  private static final byte          IS_DIRTY                    = 2;
  private static final long          SLEEP_TIME_ON_STARTUP_ERROR = 500;
  private static final int           STARTUP_RETRY_COUNT         = 5;

  private final List                 createdDatabases;
  private final Map                  databasesByName;
  private final File                 envHome;
  private EnvironmentConfig          ecfg;
  private DatabaseConfig             dbcfg;
  private ClassCatalogWrapper        catalog;

  private Environment                env;
  private Database                   controlDB;
  private DBEnvironmentStatus        status                      = DBEnvironmentStatus.STATUS_INIT;
  private boolean                    openResult                  = false;

  private final boolean              paranoid;

  public BerkeleyDBEnvironment(boolean paranoid, File envHome) throws IOException {
    this(paranoid, envHome, new Properties());
  }

  public BerkeleyDBEnvironment(boolean paranoid, File envHome, Properties jeProperties) throws IOException {
    this(new HashMap(), new LinkedList(), paranoid, envHome);
    this.ecfg = new EnvironmentConfig(jeProperties);
    this.ecfg.setTransactional(true);
    this.ecfg.setAllowCreate(true);
    this.ecfg.setReadOnly(false);
    // this.ecfg.setTxnWriteNoSync(!paranoid);
    this.ecfg.setTxnNoSync(!paranoid);
    this.dbcfg = new DatabaseConfig();
    this.dbcfg.setAllowCreate(true);
    this.dbcfg.setTransactional(true);

    logger.info("Env config = " + this.ecfg + " DB Config = " + this.dbcfg + " JE Properties = " + jeProperties);
  }

  public void initBackupMbean(ServerDBBackupMBean mBean) throws TCDatabaseException {
    if (mBean != null) {
      ((ServerDBBackup) mBean).setDbEnvironment(this.getEnvironment(), this.getEnvironmentHome());
    }
  }

  // For tests
  BerkeleyDBEnvironment(boolean paranoid, File envHome, EnvironmentConfig ecfg, DatabaseConfig dbcfg)
      throws IOException {
    this(new HashMap(), new LinkedList(), paranoid, envHome, ecfg, dbcfg);
  }

  // For tests
  public BerkeleyDBEnvironment(Map databasesByName, List createdDatabases, boolean paranoid, File envHome,
                               EnvironmentConfig ecfg, DatabaseConfig dbcfg) throws IOException {
    this(databasesByName, createdDatabases, paranoid, envHome);
    this.ecfg = ecfg;
    this.dbcfg = dbcfg;
  }

  /**
   * Note: it is not currently safe to create more than one of these instances in the same process. Sleepycat is
   * supposed to keep more than one process from opening a writable handle to the same database, but it allows you to
   * create more than one writable handle within the same process. So, don't do that.
   */
  private BerkeleyDBEnvironment(Map databasesByName, List createdDatabases, boolean paranoid, File envHome)
      throws IOException {
    this.databasesByName = databasesByName;
    this.createdDatabases = createdDatabases;
    this.paranoid = paranoid;
    this.envHome = envHome;
    FileUtils.forceMkdir(this.envHome);
  }

  public boolean isParanoidMode() {
    return paranoid;
  }

  public synchronized boolean open() throws TCDatabaseException {
    if ((status != DBEnvironmentStatus.STATUS_INIT) && (status != DBEnvironmentStatus.STATUS_CLOSED)) { throw new DatabaseOpenException(
                                                                                                                                        "Database environment isn't in INIT/CLOSED state."); }

    status = DBEnvironmentStatus.STATUS_OPENING;
    try {
      env = openEnvironment();
      synchronized (CONTROL_LOCK) {
        // XXX: Note: this doesn't guard against multiple instances in different
        // classloaders...
        controlDB = env.openDatabase(null, "control", this.dbcfg);
        openResult = isClean();
        if (!openResult) {
          this.status = DBEnvironmentStatus.STATUS_INIT;
          forceClose();
          return openResult;
        }
      }
      if (!this.paranoid) setDirty();
      this.catalog = new ClassCatalogWrapper(env, dbcfg);
      newDatabase(env, GLOBAL_SEQUENCE_DATABASE);
      newObjectDB(env, OBJECT_DB_NAME);
      newBytesBytesDB(env, OBJECT_OID_STORE_DB_NAME);
      newBytesBytesDB(env, MAPS_OID_STORE_DB_NAME);
      newBytesBytesDB(env, OID_STORE_LOG_DB_NAME);
      newBytesBytesDB(env, EVICTABLE_OID_STORE_DB_NAME);
      newRootDB(env, ROOT_DB_NAME);

      newLongDB(env, CLIENT_STATE_DB_NAME);
      newBytesBytesDB(env, TRANSACTION_DB_NAME);
      newLongToStringDatabase(env, STRING_INDEX_DB_NAME);
      newIntToBytesDatabase(env, CLASS_DB_NAME);
      newMapsDatabase(env, MAP_DB_NAME);
      newStringToStringDatabase(env, CLUSTER_STATE_STORE);
    } catch (DatabaseException e) {
      this.status = DBEnvironmentStatus.STATUS_ERROR;
      forceClose();
      throw new TCDatabaseException(e);
    } catch (Error e) {
      this.status = DBEnvironmentStatus.STATUS_ERROR;
      forceClose();
      throw e;
    } catch (RuntimeException e) {
      this.status = DBEnvironmentStatus.STATUS_ERROR;
      forceClose();
      throw e;
    }

    this.status = DBEnvironmentStatus.STATUS_OPEN;
    return openResult;
  }

  private void cinfo(Object message) {
    clogger.info("DB Environment: " + message);
  }

  public synchronized void close() throws TCDatabaseException {
    assertOpen();
    status = DBEnvironmentStatus.STATUS_CLOSING;
    cinfo("Closing...");

    try {
      for (Iterator i = createdDatabases.iterator(); i.hasNext();) {
        Object o = i.next();
        Database db = null;
        if (o instanceof AbstractBerkeleyDatabase) {
          db = ((AbstractBerkeleyDatabase) o).getDatabase();
        } else {
          db = (Database) o;
        }

        cinfo("Closing database: " + db.getDatabaseName() + "...");
        db.close();
      }
      cinfo("Closing class catalog...");
      this.catalog.close();
      setClean();
      if (this.controlDB != null) {
        cinfo("Closing control database...");
        this.controlDB.close();
      }
      if (this.env != null) {
        cinfo("Closing environment...");
        this.env.close();
      }
    } catch (Exception de) {
      de.printStackTrace();
      throw new TCDatabaseException(de.getMessage());
    }
    this.controlDB = null;
    this.env = null;

    status = DBEnvironmentStatus.STATUS_CLOSED;
    cinfo("Closed.");
  }

  public synchronized boolean isOpen() {
    return DBEnvironmentStatus.STATUS_OPEN.equals(status);
  }

  // This is for testing and cleanup on error.
  synchronized void forceClose() {
    List toClose = new ArrayList(createdDatabases);
    toClose.add(controlDB);
    for (Iterator i = toClose.iterator(); i.hasNext();) {
      try {
        Object o = i.next();
        Database db = null;
        if (o instanceof AbstractBerkeleyDatabase) {
          db = ((AbstractBerkeleyDatabase) o).getDatabase();
        } else {
          db = (Database) o;
        }
        if (db != null) db.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    try {
      if (this.catalog != null) this.catalog.close();
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      if (env != null) env.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public File getEnvironmentHome() {
    return envHome;
  }

  public synchronized Environment getEnvironment() throws TCDatabaseException {
    assertOpen();
    return env;
  }

  public EnvironmentStats getStats() throws TCDatabaseException {
    final StatsConfig sc = new StatsConfig();
    sc.setClear(true);
    try {
      return env.getStats(sc);
    } catch (Exception e) {
      throw new TCDatabaseException(e.getMessage());
    }
  }

  public synchronized TCObjectDatabase getObjectDatabase() throws TCDatabaseException {
    assertOpen();
    return (TCObjectDatabase) databasesByName.get(OBJECT_DB_NAME);
  }

  public synchronized TCBytesToBytesDatabase getObjectOidStoreDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCBytesBytesDatabase) databasesByName.get(OBJECT_OID_STORE_DB_NAME);
  }

  public synchronized TCBytesToBytesDatabase getMapsOidStoreDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCBytesBytesDatabase) databasesByName.get(MAPS_OID_STORE_DB_NAME);
  }

  public synchronized TCBytesToBytesDatabase getOidStoreLogDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCBytesBytesDatabase) databasesByName.get(OID_STORE_LOG_DB_NAME);
  }

  public TCBytesToBytesDatabase getEvictableOidStoreDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCBytesBytesDatabase) databasesByName.get(EVICTABLE_OID_STORE_DB_NAME);
  }

  public synchronized ClassCatalogWrapper getClassCatalogWrapper() throws TCDatabaseException {
    assertOpen();
    return catalog;
  }

  public synchronized TCRootDatabase getRootDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCRootDatabase) databasesByName.get(ROOT_DB_NAME);
  }

  public TCLongDatabase getClientStateDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCLongDatabase) databasesByName.get(CLIENT_STATE_DB_NAME);
  }

  public TCBytesToBytesDatabase getTransactionDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCBytesBytesDatabase) databasesByName.get(TRANSACTION_DB_NAME);
  }

  public TCIntToBytesDatabase getClassDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCIntToBytesDatabase) databasesByName.get(CLASS_DB_NAME);
  }

  public TCMapsDatabase getMapsDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCMapsDatabase) databasesByName.get(MAP_DB_NAME);
  }

  public TCLongToStringDatabase getStringIndexDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCLongToStringDatabase) databasesByName.get(STRING_INDEX_DB_NAME);
  }

  public TCStringToStringDatabase getClusterStateStoreDatabase() throws TCDatabaseException {
    assertOpen();
    return (BerkeleyDBTCStringtoStringDatabase) databasesByName.get(CLUSTER_STATE_STORE);
  }

  private void assertNotError() throws TCDatabaseException {
    if (DBEnvironmentStatus.STATUS_ERROR == status) throw new TCDatabaseException(
                                                                                  "Attempt to operate on an environment in an error state.");
  }

  private void assertOpening() {
    if (DBEnvironmentStatus.STATUS_OPENING != status) throw new AssertionError(
                                                                               "Database environment should be opening but isn't");
  }

  private void assertOpen() throws TCDatabaseException {
    assertNotError();
    if (DBEnvironmentStatus.STATUS_OPEN != status) throw new DatabaseNotOpenException(
                                                                                      "Database environment should be open but isn't.");
  }

  private void assertClosing() {
    if (DBEnvironmentStatus.STATUS_CLOSING != status) throw new AssertionError(
                                                                               "Database environment should be closing but isn't");
  }

  private boolean isClean() throws TCDatabaseException {
    assertOpening();
    DatabaseEntry value = new DatabaseEntry(new byte[] { 0 });
    Transaction tx = newTransaction();
    OperationStatus stat;
    try {
      stat = controlDB.get(tx, CLEAN_FLAG_KEY, value, LockMode.DEFAULT);
      tx.commit();
    } catch (Exception e) {
      throw new TCDatabaseException(e.getMessage());
    }
    return OperationStatus.NOTFOUND.equals(stat)
           || (OperationStatus.SUCCESS.equals(stat) && value.getData()[0] == IS_CLEAN);
  }

  private void setDirty() throws TCDatabaseException {
    assertOpening();
    DatabaseEntry value = new DatabaseEntry(new byte[] { IS_DIRTY });
    Transaction tx = newTransaction();
    OperationStatus stat;
    try {
      stat = controlDB.put(tx, CLEAN_FLAG_KEY, value);
    } catch (Exception e) {
      throw new TCDatabaseException(e.getMessage());
    }
    if (!OperationStatus.SUCCESS.equals(stat)) throw new TCDatabaseException("Unexpected operation status "
                                                                             + "trying to unset clean flag: " + stat);
    try {
      tx.commitSync();
    } catch (Exception e) {
      throw new TCDatabaseException(e.getMessage());
    }
  }

  private Transaction newTransaction() throws TCDatabaseException {
    try {
      Transaction tx = env.beginTransaction(null, null);
      return tx;
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void setClean() throws TCDatabaseException {
    assertClosing();
    DatabaseEntry value = new DatabaseEntry(new byte[] { IS_CLEAN });
    Transaction tx = newTransaction();
    OperationStatus stat;
    try {
      stat = controlDB.put(tx, CLEAN_FLAG_KEY, value);
    } catch (Exception e) {
      throw new TCDatabaseException(e.getMessage());
    }
    if (!OperationStatus.SUCCESS.equals(stat)) throw new TCDatabaseException("Unexpected operation status "
                                                                             + "trying to set clean flag: " + stat);
    try {
      tx.commitSync();
    } catch (Exception e) {
      throw new TCDatabaseException(e.getMessage());
    }
  }

  private void newObjectDB(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCObjectDatabase objectDatabse = new BerkeleyDBTCObjectDatabase(db);

      createdDatabases.add(objectDatabse);
      databasesByName.put(name, objectDatabse);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newRootDB(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCRootDatabase bdb = new BerkeleyDBTCRootDatabase(db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newBytesBytesDB(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCBytesBytesDatabase bdb = new BerkeleyDBTCBytesBytesDatabase(db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newLongDB(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCLongDatabase bdb = new BerkeleyDBTCLongDatabase(db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newIntToBytesDatabase(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCIntToBytesDatabase bdb = new BerkeleyDBTCIntToBytesDatabase(db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newLongToStringDatabase(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCLongToStringDatabase bdb = new BerkeleyDBTCLongToStringDatabase(catalog.getClassCatalog(), db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newStringToStringDatabase(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCStringtoStringDatabase bdb = new BerkeleyDBTCStringtoStringDatabase(db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newMapsDatabase(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      BerkeleyDBTCMapsDatabase bdb = new BerkeleyDBTCMapsDatabase(db);
      createdDatabases.add(bdb);
      databasesByName.put(name, bdb);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private void newDatabase(Environment e, String name) throws TCDatabaseException {
    try {
      Database db = e.openDatabase(null, name, dbcfg);
      createdDatabases.add(db);
      databasesByName.put(name, db);
    } catch (Exception de) {
      throw new TCDatabaseException(de.getMessage());
    }
  }

  private Environment openEnvironment() throws TCDatabaseException {
    int count = 0;
    while (true) {
      try {
        return new Environment(envHome, ecfg);
      } catch (Exception dbe) {
        if (++count <= STARTUP_RETRY_COUNT) {
          logger.warn("Unable to open DB environment. " + dbe.getMessage() + " Retrying after "
                      + SLEEP_TIME_ON_STARTUP_ERROR + " ms");
          ThreadUtil.reallySleep(SLEEP_TIME_ON_STARTUP_ERROR);
        } else {
          throw new TCDatabaseException(dbe.getMessage());
        }
      }
    }
  }

  public static final String getClusterStateStoreName() {
    return CLUSTER_STATE_STORE;
  }

  public static final class ClassCatalogWrapper {

    private final StoredClassCatalog catalog;
    private boolean                  closed = false;

    private ClassCatalogWrapper(Environment env, DatabaseConfig cfg) throws DatabaseException {
      catalog = new StoredClassCatalog(env.openDatabase(null, "java_class_catalog", cfg));
    }

    public final ClassCatalog getClassCatalog() {
      return this.catalog;
    }

    synchronized void close() throws DatabaseException {
      if (closed) throw new IllegalStateException("Already closed.");
      this.catalog.close();
      closed = true;
    }
  }

  public MutableSequence getSequence(PersistenceTransactionProvider ptxp, TCLogger log, String sequenceID,
                                     int startValue) {
    return new BerkeleyDBSequence(ptxp, log, sequenceID, startValue,
                                  (Database) databasesByName.get(GLOBAL_SEQUENCE_DATABASE));
  }

  public PersistenceTransactionProvider getPersistenceTransactionProvider() {
    try {
      return new BerkeleyDBPersistenceTransactionProvider(getEnvironment());
    } catch (TCDatabaseException e) {
      throw new DBException(e);
    }
  }
}