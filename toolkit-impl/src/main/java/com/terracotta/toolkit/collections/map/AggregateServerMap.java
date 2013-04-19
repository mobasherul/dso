/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections.map;

import net.sf.ehcache.pool.SizeOfEngine;
import net.sf.ehcache.pool.impl.DefaultSizeOfEngine;
import org.terracotta.toolkit.ToolkitObjectType;
import org.terracotta.toolkit.ToolkitRuntimeException;
import org.terracotta.toolkit.cache.ToolkitCacheListener;
import org.terracotta.toolkit.cluster.ClusterNode;
import org.terracotta.toolkit.collections.ToolkitMap;
import org.terracotta.toolkit.concurrent.locks.ToolkitLock;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;
import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.concurrent.locks.ToolkitLockTypeInternal;
import org.terracotta.toolkit.rejoin.RejoinException;
import org.terracotta.toolkit.search.QueryBuilder;
import org.terracotta.toolkit.search.SearchQueryResultSet;
import org.terracotta.toolkit.search.ToolkitSearchQuery;
import org.terracotta.toolkit.search.attribute.ToolkitAttributeExtractor;
import org.terracotta.toolkit.store.ToolkitConfigFields;
import org.terracotta.toolkit.store.ToolkitConfigFields.Consistency;

import com.google.common.base.Preconditions;
import com.tc.abortable.AbortedOperationException;
import com.tc.exception.PlatformRejoinException;
import com.tc.exception.TCNotRunningException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.InterestDestination;
import com.tc.object.InterestType;
import com.tc.object.LiteralValues;
import com.tc.object.ObjectID;
import com.tc.object.TCObject;
import com.tc.object.TCObjectServerMap;
import com.tc.object.servermap.localcache.L1ServerMapLocalCacheStore;
import com.tc.object.servermap.localcache.PinnedEntryFaultCallback;
import com.tc.platform.PlatformService;
import com.terracotta.toolkit.abortable.ToolkitAbortableOperationException;
import com.terracotta.toolkit.cluster.TerracottaClusterInfo;
import com.terracotta.toolkit.collections.map.ServerMap.GetType;
import com.terracotta.toolkit.collections.map.ToolkitMapAggregateSet.ClusteredMapAggregateEntrySet;
import com.terracotta.toolkit.collections.map.ToolkitMapAggregateSet.ClusteredMapAggregateKeySet;
import com.terracotta.toolkit.collections.map.ToolkitMapAggregateSet.ClusteredMapAggregatedValuesCollection;
import com.terracotta.toolkit.collections.servermap.L1ServerMapLocalCacheStoreImpl;
import com.terracotta.toolkit.collections.servermap.api.ServerMapLocalStore;
import com.terracotta.toolkit.collections.servermap.api.ServerMapLocalStoreConfig;
import com.terracotta.toolkit.collections.servermap.api.ServerMapLocalStoreConfigParameters;
import com.terracotta.toolkit.collections.servermap.api.ServerMapLocalStoreFactory;
import com.terracotta.toolkit.concurrent.locks.ToolkitLockingApi;
import com.terracotta.toolkit.config.ConfigChangeListener;
import com.terracotta.toolkit.config.ImmutableConfiguration;
import com.terracotta.toolkit.config.UnclusteredConfiguration;
import com.terracotta.toolkit.config.cache.InternalCacheConfigurationType;
import com.terracotta.toolkit.object.DestroyApplicator;
import com.terracotta.toolkit.object.ToolkitObjectStripe;
import com.terracotta.toolkit.search.SearchFactory;
import com.terracotta.toolkit.search.SearchableEntity;
import com.terracotta.toolkit.type.DistributedClusteredObjectLookup;
import com.terracotta.toolkit.type.DistributedToolkitType;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static com.terracotta.toolkit.config.ConfigUtil.distributeInStripes;

public class AggregateServerMap<K, V> implements DistributedToolkitType<InternalToolkitMap<K, V>>,
    ToolkitCacheImplInterface<K, V>, ConfigChangeListener, ValuesResolver<K, V>, SearchableEntity, InterestDestination {
  private static final TCLogger                                            LOGGER                               = TCLogging
                                                                                                                    .getLogger(AggregateServerMap.class);
  private static final int                                                 KB                                   = 1024;
  private static final String                                              EHCACHE_BULKOPS_MAX_KB_SIZE_PROPERTY = "ehcache.bulkOps.maxKBSize";
  private static final int                                                 DEFAULT_EHCACHE_BULKOPS_MAX_KB_SIZE  = KB;

  public static final int                                                  DEFAULT_MAX_SIZEOF_DEPTH             = 1000;

  private static final String                                              EHCACHE_GETALL_BATCH_SIZE_PROPERTY   = "ehcache.getAll.batchSize";
  private static final int                                                 DEFAULT_GETALL_BATCH_SIZE            = 1000;
  private final static String                                              CONFIG_CHANGE_LOCK_ID                = "__tc_config_change_lock";
  private final static List<ToolkitObjectType>                             VALID_TYPES                          = Arrays
                                                                                                                    .asList(ToolkitObjectType.STORE,
                                                                                                                            ToolkitObjectType.CACHE);

  private final int                                                        bulkOpsKbSize;
  private final int                                                        getAllBatchSize;
  private final ToolkitLock                                                eventualBulkOpsConcurrentLock;

  protected volatile InternalToolkitMap<K, V>[]                            serverMaps;
  protected final String                                                   name;
  protected final UnclusteredConfiguration                                 config;
  protected final CopyOnWriteArrayList<ToolkitCacheListener<K>>            listeners;
  private volatile ToolkitObjectStripe<InternalToolkitMap<K, V>>[]         stripeObjects;
  private final Consistency                                                consistency;
  private final SizeOfEngine                                               sizeOfEngine;
  private final TimeSource                                                 timeSource;
  private final SearchFactory                                              searchBuilderFactory;
  private final ServerMapLocalStoreFactory                                 serverMapLocalStoreFactory;
  private final TerracottaClusterInfo                                      clusterInfo;
  private final PlatformService                                            platformService;
  private final Callable<ToolkitMap<String, String>>                       schemaCreator;
  private final DistributedClusteredObjectLookup<InternalToolkitMap<K, V>> lookup;
  private final ToolkitObjectType                                          toolkitObjectType;
  private final L1ServerMapLocalCacheStore<K, V>                           localCacheStore;
  private final PinnedEntryFaultCallback                                   pinnedEntryFaultCallback;
  private volatile boolean                                                 lookupSuccessfulAfterRejoin;
  private final AtomicReference<ToolkitMap<String, String>>                attrSchema                           = new AtomicReference<ToolkitMap<String, String>>();

  private int getTerracottaProperty(String propName, int defaultValue) {
    try {
      return platformService.getTCProperties().getInt(propName, defaultValue);
    } catch (UnsupportedOperationException e) {
      // for unit-tests
      return defaultValue;
    }
  }

  public AggregateServerMap(ToolkitObjectType type, SearchFactory searchBuilderFactory,
                            DistributedClusteredObjectLookup<InternalToolkitMap<K, V>> lookup, String name,
                            ToolkitObjectStripe<InternalToolkitMap<K, V>>[] stripeObjects, Configuration config,
 Callable<ToolkitMap<String, String>> schemaCreator,
                            ServerMapLocalStoreFactory serverMapLocalStoreFactory, PlatformService platformService) {
    this.toolkitObjectType = type;
    this.searchBuilderFactory = searchBuilderFactory;
    this.lookup = lookup;
    this.platformService = platformService;
    this.clusterInfo = new TerracottaClusterInfo(platformService);
    this.bulkOpsKbSize = getTerracottaProperty(EHCACHE_BULKOPS_MAX_KB_SIZE_PROPERTY,
                                               DEFAULT_EHCACHE_BULKOPS_MAX_KB_SIZE) * KB;
    this.getAllBatchSize = getTerracottaProperty(EHCACHE_GETALL_BATCH_SIZE_PROPERTY, DEFAULT_GETALL_BATCH_SIZE);
    this.eventualBulkOpsConcurrentLock = ToolkitLockingApi
        .createConcurrentTransactionLock("bulkops-static-eventual-concurrent-lock", platformService);

    this.serverMapLocalStoreFactory = serverMapLocalStoreFactory;
    Preconditions.checkArgument(isValidType(type), "Type has to be one of %s but was %s", VALID_TYPES, type);

    this.name = name;
    Preconditions.checkNotNull(schemaCreator);
    this.schemaCreator = schemaCreator;
    this.listeners = new CopyOnWriteArrayList<ToolkitCacheListener<K>>();

    this.config = new UnclusteredConfiguration(config);
    this.consistency = Consistency.valueOf((String) InternalCacheConfigurationType.CONSISTENCY
        .getExistingValueOrException(config));
    this.sizeOfEngine = new DefaultSizeOfEngine(DEFAULT_MAX_SIZEOF_DEPTH, true);

    localCacheStore = createLocalCacheStore();
    pinnedEntryFaultCallback = new PinnedEntryFaultCallbackImpl(this);
    this.timeSource = new SystemTimeSource();
    setupStripeObjects(stripeObjects);

  }

  private void setupStripeObjects(ToolkitObjectStripe<InternalToolkitMap<K, V>>[] stripeObjects) {
    this.stripeObjects = stripeObjects;
    List<InternalToolkitMap<K, V>> list = new ArrayList<InternalToolkitMap<K, V>>();
    for (ToolkitObjectStripe<InternalToolkitMap<K, V>> stripeObject : stripeObjects) {
      for (InternalToolkitMap<K, V> serverMap : stripeObject) {
        list.add(serverMap);
      }
    }
    initializeLocalCache(list);
    this.serverMaps = list.toArray(new ServerMap[list.size()]);
    for (ToolkitObjectStripe stripeObject : stripeObjects) {
      stripeObject.addConfigChangeListener(this);
    }
  }

  private static boolean isValidType(ToolkitObjectType toolkitObjectType) {
    for (ToolkitObjectType validType : VALID_TYPES) {
      if (validType == toolkitObjectType) return true;
    }
    return false;
  }

  private void initializeLocalCache(List<InternalToolkitMap<K, V>> serverMapsParam) {
    boolean localCacheEnabled = (Boolean) InternalCacheConfigurationType.LOCAL_CACHE_ENABLED
        .getValueIfExistsOrDefault(config);
    for (InternalToolkitMap<K, V> serverMap : serverMapsParam) {
      serverMap.initializeLocalCache(localCacheStore, pinnedEntryFaultCallback, localCacheEnabled);
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void rejoinStarted() {
    // nothing to do
  }

  @Override
  public void rejoinCompleted() {
    getAnyServerMap().cleanLocalState();
    ToolkitObjectStripe<InternalToolkitMap<K, V>>[] objects = lookup.lookupStripeObjects(name, this.toolkitObjectType,
                                                                                         config);
    if (objects != null) {
      setupStripeObjects(objects);
      lookupSuccessfulAfterRejoin = true;
    } else {
      lookupSuccessfulAfterRejoin = false;
    }
  }

  protected boolean isLookupSuccessfulAfterRejoin() {
    return lookupSuccessfulAfterRejoin;
  }

  private L1ServerMapLocalCacheStore<K, V> createLocalCacheStore() {
    ServerMapLocalStore<K, V> smLocalStore = serverMapLocalStoreFactory
        .getOrCreateServerMapLocalStore(getLocalStoreConfig());
    return new L1ServerMapLocalCacheStoreImpl<K, V>(smLocalStore);
  }

  private ServerMapLocalStoreConfig getLocalStoreConfig() {
    return new ServerMapLocalStoreConfigParameters().populateFrom(config, this.name).buildConfig();
  }

  protected InternalToolkitMap<K, V> getServerMapForKey(Object key) {
    Preconditions.checkNotNull(key, "Key cannot be null");
    return serverMaps[Math.abs(key.hashCode() % serverMaps.length)];
  }

  protected InternalToolkitMap<K, V> getAnyServerMap() {
    return serverMaps[0];
  }

  private TCObjectServerMap getAnyTCObjectServerMap() {
    final InternalToolkitMap<K, V> e = getAnyServerMap();
    if (e == null || e.__tc_managed() == null) { throw new UnsupportedOperationException("Map is not shared ServerMap"); }
    return (TCObjectServerMap) e.__tc_managed();
  }

  @Override
  public ToolkitReadWriteLock createLockForKey(K key) {
    return getServerMapForKey(key).createLockForKey(key);
  }

  @Override
  public int size() {
    // wait and then tell me more accurate size
    try {
      platformService.waitForAllCurrentTransactionsToComplete();
    } catch (AbortedOperationException e) {
      throw new ToolkitAbortableOperationException(e);
    }
    long sum;
    try {
      sum = getAnyTCObjectServerMap().getAllSize(serverMaps);
    } catch (AbortedOperationException e) {
      throw new ToolkitAbortableOperationException(e);
    } catch (PlatformRejoinException e) {
      throw new RejoinException(e);
    }
    // copy the way CHM does if overflow integer
    if (sum > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return (int) sum;
    }
  }

  @Override
  public boolean isEmpty() {
    return this.size() == 0;
  }

  @Override
  public boolean containsKey(Object key) {
    return getServerMapForKey(key).containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public V get(Object key) {
    return getServerMapForKey(key).get(key);
  }

  @Override
  public V get(K key, ObjectID valueOid) {
    return getServerMapForKey(key).get(key, valueOid);
  }

  @Override
  public V remove(Object key) {
    return getServerMapForKey(key).remove(key);
  }

  @Override
  public void clear() {
    for (InternalToolkitMap<K, V> map : serverMaps) {
      map.clear();
    }
    try {
      platformService.waitForAllCurrentTransactionsToComplete();
    } catch (AbortedOperationException e) {
      throw new ToolkitAbortableOperationException(e);
    }
    clearLocalCache();
  }

  @Override
  public Set<K> keySet() {
    return new ClusteredMapAggregateKeySet<K, V>(this);
  }

  @Override
  public Collection<V> values() {
    return new ClusteredMapAggregatedValuesCollection<K, V>(this);
  }

  @Override
  public Set<Map.Entry<K, V>> entrySet() {
    return new ClusteredMapAggregateEntrySet<K, V>(this);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return getServerMapForKey(key).remove(key, value);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    return getServerMapForKey(key).replace(key, oldValue, newValue);
  }

  @Override
  public V replace(K key, V value) {
    return getServerMapForKey(key).replace(key, value);
  }

  @Override
  public void removeNoReturn(Object key) {
    getServerMapForKey(key).removeNoReturn(key);
  }

  @Override
  public V unsafeLocalGet(Object key) {
    return getServerMapForKey(key).unsafeLocalGet(key);
  }

  @Override
  public V unlockedGet(Object key, boolean quiet) {
    return getServerMapForKey(key).unlockedGet((K) key, quiet);
  }

  @Override
  public void putNoReturn(K key, V value) {
    putNoReturn(key, value, timeSource.nowInSeconds(), ToolkitConfigFields.NO_MAX_TTI_SECONDS,
                ToolkitConfigFields.NO_MAX_TTL_SECONDS);
  }

  @Override
  public int localSize() {
    return getAnyServerMap().localSize();
  }

  @Override
  public Set<K> localKeySet() {
    return getAnyServerMap().localKeySet();
  }

  @Override
  public boolean containsLocalKey(Object key) {
    return getServerMapForKey(key).containsLocalKey(key);
  }

  @Override
  public void addListener(ToolkitCacheListener<K> listener) {
    // synchronize not to have duplicate listeners
    synchronized (listeners) {
      if (!listeners.contains(listener)) {
        this.listeners.add(listener);
      }
    }
    platformService.registerL1CacheListener(this, EnumSet.of(InterestType.EVICT, InterestType.EXPIRE));
    LOGGER.info("A new listener has been registered. Cache: " + getName());
  }

  @Override
  public void removeListener(ToolkitCacheListener<K> listener) {
    synchronized (listeners) {
      this.listeners.remove(listener);
    }
    //platformService.unregister
  }

  @Override
  public void putNoReturn(K key, V value, long createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds) {
    getServerMapForKey(key).putNoReturn(key, value, (int) createTimeInSecs, customMaxTTISeconds, customMaxTTLSeconds);
  }

  @Override
  public V putIfAbsent(K key, V value, long createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds) {
    return getServerMapForKey(key).putIfAbsent(key, value, (int) createTimeInSecs, customMaxTTISeconds,
                                               customMaxTTLSeconds);
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return getServerMapForKey(key).putIfAbsent(key, value);
  }

  @Override
  public SearchQueryResultSet executeQuery(ToolkitSearchQuery query) {
    return searchBuilderFactory.createSearchExecutor(getName(), getToolkitObjectType(), this,
                                                     getAnyServerMap().isEventual(), platformService)
        .executeQuery(query);
  }

  public void setApplyDestroyCallback(DestroyApplicator destroyCallback) {
    getAnyServerMap().setApplyDestroyCallback(destroyCallback);
  }

  @Override
  public void destroy() {
    for (InternalToolkitMap serverMap : serverMaps) {
      serverMap.destroy();
    }
    if (attrSchema.get() != null) attrSchema.get().destroy();
  }

  @Override
  public void disposeLocally() {
    // Need to wait for all transactions to complete since there could still be in-flight transactions dependent on the
    // local cache.
    try {
      platformService.waitForAllCurrentTransactionsToComplete();
    } catch (TCNotRunningException e) {
      LOGGER.info("Ignoring " + TCNotRunningException.class.getName()
                  + " while waiting for all current txns to complete");
    } catch (AbortedOperationException e) {
      throw new ToolkitAbortableOperationException(e);
    } finally {
      try {
        getAnyServerMap().disposeLocally();
      } catch (TCNotRunningException e) {
        LOGGER.info("Ignoring " + TCNotRunningException.class.getName() + " while destroying local cache");
      }
    }
  }

  @Override
  public Map<K, V> getAll(Collection<? extends K> keys) {
    return doGetAll(keys, false);
  }

  @Override
  public Map<K, V> getAllQuiet(Collection<K> keys) {
    return doGetAll(keys, true);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    if (map == null || map.isEmpty()) { return; }
    if (isExplicitLocked()) { throw new UnsupportedOperationException(); }
    switch (consistency) {
      case STRONG:
      case SYNCHRONOUS_STRONG:
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
          putNoReturn(entry.getKey(), entry.getValue());
        }
        break;
      case EVENTUAL:
        unlockedPutAll(map);
    }
  }

  private <L extends K, W extends V> void unlockedPutAll(Map<L, W> map) {
    Iterator<Map.Entry<L, W>> iter = map.entrySet().iterator();
    while (iter.hasNext()) {
      Map<K, V> batchedEntries = createPutAllBatch(iter);
      commitPutAllBatch(batchedEntries);
    }
  }

  private void commitPutAllBatch(Map<? extends K, ? extends V> batchedEntries) {
    eventualBulkOpsConcurrentLock.lock();
    try {
      for (Map.Entry<? extends K, ? extends V> entry : batchedEntries.entrySet()) {
        int now = timeSource.nowInSeconds();
        unlockedPutNoReturn(entry.getKey(), entry.getValue(), now, ToolkitConfigFields.NO_MAX_TTI_SECONDS,
                            ToolkitConfigFields.NO_MAX_TTL_SECONDS);
      }
    } finally {
      eventualBulkOpsConcurrentLock.unlock();
    }
  }

  private <L extends K, W extends V> Map<K, V> createPutAllBatch(Iterator<Map.Entry<L, W>> iter) {
    long currentByteSize = 0;
    Map<K, V> batchedEntries = new HashMap<K, V>();
    while (currentByteSize < bulkOpsKbSize && iter.hasNext()) {
      Map.Entry<? extends K, ? extends V> entry = iter.next();
      currentByteSize += getEntrySize(entry, false);
      batchedEntries.put(entry.getKey(), entry.getValue());
    }
    return batchedEntries;
  }

  @Override
  public void removeAll(Set<K> keys) {
    if (keys == null || keys.isEmpty()) { return; }
    if (isExplicitLocked()) { throw new UnsupportedOperationException(); }
    switch (consistency) {
      case STRONG:
      case SYNCHRONOUS_STRONG:
        for (K key : keys) {
          removeNoReturn(key);
        }
        break;
      case EVENTUAL:
        Iterator<K> iter = keys.iterator();
        while (iter.hasNext()) {
          long currentByteSize = 0;
          Set<K> batchedEntries = createRemoveAllBatch(iter, currentByteSize);
          commitRemoveAllBatch(batchedEntries);
        }
    }
  }

  private void commitRemoveAllBatch(Set<K> batchedEntries) {
    eventualBulkOpsConcurrentLock.lock();
    try {
      for (K key : batchedEntries) {
        unlockedRemoveNoReturn(key);
      }
    } finally {
      eventualBulkOpsConcurrentLock.unlock();
    }
  }

  private Set<K> createRemoveAllBatch(Iterator<K> iter, long currentByteSize) {
    Set<K> batchedEntries = new HashSet<K>();
    while (currentByteSize < bulkOpsKbSize && iter.hasNext()) {
      K key = iter.next();
      currentByteSize += sizeOfEngine.sizeOf(key, null, null).getCalculated();
      batchedEntries.add(key);
    }
    return batchedEntries;
  }

  private long getEntrySize(Entry entry, boolean withMetaData) {
    // TODO: fix to include metadata size
    return sizeOfEngine.sizeOf(entry.getKey(), entry.getValue(), null).getCalculated();
  }

  private Map<K, V> doGetAll(final Collection<? extends K> keys, boolean quiet) {
    if (keys == null || keys.isEmpty()) { return Collections.emptyMap(); }
    if (isExplicitLocked()) { throw new UnsupportedOperationException(); }
    switch (consistency) {
      case STRONG:
      case SYNCHRONOUS_STRONG:
        Map<K, V> rv = new HashMap<K, V>();
        if (quiet) {
          for (K key : keys) {
            rv.put(key, getQuiet(key));
          }
        } else {
          for (K key : keys) {
            rv.put(key, get(key));
          }
        }
        return rv;
      case EVENTUAL:
        return unlockedGetAll((Collection<K>) keys, quiet);
    }
    throw new UnsupportedOperationException("Unknown consistency - " + consistency);
  }

  Map<K, V> getAllInternal(Set<K> keys, boolean quiet) {
    final Map<ObjectID, Set<K>> mapIdToKeysMap = new HashMap<ObjectID, Set<K>>();
    divideKeysIntoServerMaps(keys, mapIdToKeysMap);
    TCObjectServerMap tcObjectServerMap = getAnyTCObjectServerMap();
    Map<K, V> rv = null;
    try {
      rv = tcObjectServerMap.getAllValuesUnlocked(mapIdToKeysMap);
    } catch (AbortedOperationException e) {
      throw new ToolkitAbortableOperationException(e);
    } catch (PlatformRejoinException e) {
      throw new RejoinException(e);
    }

    for (Entry<K, V> entry : rv.entrySet()) {
      V nonExpiredValue = getServerMapForKey(entry.getKey()).checkAndGetNonExpiredValue(entry.getKey(),
                                                                                        entry.getValue(),
                                                                                        GetType.UNLOCKED, quiet);
      entry.setValue(nonExpiredValue);
    }
    return rv;
  }

  private void divideKeysIntoServerMaps(Set<K> keys, final Map<ObjectID, Set<K>> mapIdToKeysMap) {
    for (K key : keys) {
      InternalToolkitMap<K, V> serverMap = getServerMapForKey(key);
      assertKeyLiteral(key);
      TCObject tcObject = serverMap.__tc_managed();
      if (tcObject == null) { throw new UnsupportedOperationException(
                                                                      "unlockedGetAll is not supported in a non-shared ServerMap"); }
      ObjectID mapId = tcObject.getObjectID();
      Set<K> keysForThisServerMap = mapIdToKeysMap.get(mapId);
      if (keysForThisServerMap == null) {
        keysForThisServerMap = new HashSet<K>();
        mapIdToKeysMap.put(mapId, keysForThisServerMap);
      }
      keysForThisServerMap.add(key);
    }
  }

  public void assertKeyLiteral(K key) {
    if (!LiteralValues.isLiteralInstance(key)) {
      //
      throw new UnsupportedOperationException("Only literal keys are supported - key: " + key);
    }
  }

  @Override
  public V getQuiet(Object key) {
    return getServerMapForKey(key).get(key, true);
  }

  @Override
  public Configuration getConfiguration() {
    return new ImmutableConfiguration(config);
  }

  @Override
  public void configChanged(final String fieldChanged, final Serializable changedValue) {
    Serializable newValue = changedValue;
    InternalCacheConfigurationType type = InternalCacheConfigurationType.getTypeFromConfigString(fieldChanged);

    if (type == null || type.isClusterWideConfig()) {
      if (fieldChanged.equals(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME)) {
        int maxTotalCount = 0;
        for (ToolkitObjectStripe stripe : stripeObjects) {
          maxTotalCount += stripe.getConfiguration().getInt(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME);
        }
        newValue = maxTotalCount;
      } else {
        for (InternalToolkitMap sm : serverMaps) {
          sm.setConfigFieldInternal(fieldChanged, newValue);
        }
      }
      config.setObject(fieldChanged, newValue);
    }

  }

  @Override
  public void setConfigField(final String fieldChanged, final Serializable changedValue) {
    ToolkitLockingApi.lock(CONFIG_CHANGE_LOCK_ID, ToolkitLockTypeInternal.CONCURRENT, platformService);
    try {
      // to prevent user from manually setting a wrong configuration option
      validateField(fieldChanged);
      Serializable newValue = changedValue;
      config.setObject(fieldChanged, newValue);
      // set config changes ServerMap
      int[] values = null;
      for (int i = 0; i < this.serverMaps.length; i++) {
        if (ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME.equals(fieldChanged)) {
          if (values == null) {
            values = distributeInStripes((Integer) newValue, this.serverMaps.length);
          }
          newValue = ((Integer) changedValue) < 0 ? -1 : values[i];
        }
        serverMaps[i].setConfigField(fieldChanged, newValue);
      }

      // set the config field in ClusteredObjectStripeImpl
      for (ToolkitObjectStripe<InternalToolkitMap<K, V>> stripe : this.stripeObjects) {
        if (ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME.equals(fieldChanged)) {
          int maxTotalCount = 0;
          for (InternalToolkitMap<K, V> sm : stripe) {
            maxTotalCount += sm.getMaxCountInCluster();
          }
          newValue = maxTotalCount < 0 ? -1 : maxTotalCount;
        }
        stripe.setConfigField(fieldChanged, newValue);
      }
    } finally {
      ToolkitLockingApi.unlock(CONFIG_CHANGE_LOCK_ID, ToolkitLockTypeInternal.CONCURRENT, platformService);
    }
  }

  private void validateField(final String fieldChanged) {
    final InternalCacheConfigurationType type = InternalCacheConfigurationType.getTypeFromConfigString(fieldChanged);
    Preconditions.checkArgument(InternalCacheConfigurationType.getConfigsFor(toolkitObjectType).contains(type),
                                "%s does not support configuration option '%s'", toolkitObjectType, fieldChanged);
    Preconditions
        .checkArgument(type.isDynamicChangeAllowed(), "Dynamic change not allowed for field: %s", fieldChanged);
  }

  @Override
  public boolean isDestroyed() {
    throw new UnsupportedOperationException();
  }

  private boolean isExplicitLocked() {
    return false;
  }

  @Override
  public void unlockedPutNoReturn(K key, V value, int createTimeInSecs, int customTTISeconds, int customTTLSeconds) {
    getServerMapForKey(key).unlockedPutNoReturn(key, value, createTimeInSecs, customTTISeconds, customTTLSeconds);
  }

  @Override
  public void unlockedRemoveNoReturn(Object key) {
    getServerMapForKey(key).unlockedRemoveNoReturn(key);
  }

  public void unlockedClear() {
    for (InternalToolkitMap<K, V> map : serverMaps) {
      map.unlockedClear();
    }
  }

  @Override
  public void clearLocalCache() {
    getAnyServerMap().clearLocalCache();
  }

  @Override
  public long localOnHeapSizeInBytes() {
    return getAnyServerMap().localOnHeapSizeInBytes();
  }

  @Override
  public long localOffHeapSizeInBytes() {
    return getAnyServerMap().localOffHeapSizeInBytes();
  }

  @Override
  public int localOnHeapSize() {
    return getAnyServerMap().localOnHeapSize();
  }

  @Override
  public int localOffHeapSize() {
    return getAnyServerMap().localOffHeapSize();
  }

  @Override
  public boolean containsKeyLocalOnHeap(Object key) {
    return getAnyServerMap().containsKeyLocalOnHeap(key);
  }

  @Override
  public boolean containsKeyLocalOffHeap(Object key) {
    return getAnyServerMap().containsKeyLocalOffHeap(key);
  }

  @Override
  public V put(K key, V value) {
    return put(key, value, timeSource.nowInSeconds(), ToolkitConfigFields.NO_MAX_TTI_SECONDS,
               ToolkitConfigFields.NO_MAX_TTL_SECONDS);
  }

  @Override
  public V put(K key, V value, int createTimeInSecs, int customMaxTTISeconds, int customMaxTTLSeconds) {
    return getServerMapForKey(key).put(key, value, createTimeInSecs, customMaxTTISeconds, customMaxTTLSeconds);
  }

  @Override
  public Map<Object, Set<ClusterNode>> getNodesWithKeys(Set keys) {
    Map<Object, Set<ClusterNode>> map = new HashMap<Object, Set<ClusterNode>>();
    for (Map m : serverMaps) {
      Map<K, Set<ClusterNode>> nodesWithKeys = clusterInfo.getNodesWithKeys(m, keys);
      for (Entry<K, Set<ClusterNode>> entry : nodesWithKeys.entrySet()) {
        Set<ClusterNode> clusterNodeSet = map.get(entry.getKey());
        if (clusterNodeSet == null) {
          clusterNodeSet = new HashSet<ClusterNode>();
          map.put(entry.getKey(), clusterNodeSet);
        }
        clusterNodeSet.addAll(entry.getValue());
      }
    }
    return map;
  }

  @Override
  public Iterator<InternalToolkitMap<K, V>> iterator() {
    return new AggregateServerMapIterator<InternalToolkitMap<K, V>>(this.serverMaps);
  }

  @Override
  public String getDestinationName() {
    return getName();
  }

  private static class AggregateServerMapIterator<E> implements Iterator<E> {
    private final E[] array;
    private int       index = 0;

    public AggregateServerMapIterator(E[] array) {
      this.array = array;
    }

    @Override
    public boolean hasNext() {
      return index < array.length;
    }

    @Override
    public E next() {
      if (!hasNext()) { throw new NoSuchElementException(); }
      index++;
      return array[index - 1];
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

  }

  @Override
  public void setAttributeExtractor(ToolkitAttributeExtractor attrExtractor) {
    // This race is okay to have, the only reason for the conditional is to avoid calling call() below
    if (attrSchema.get() == null) {
      try {
        attrSchema.compareAndSet(null, schemaCreator.call());
      } catch (Exception e) {
        throw new ToolkitRuntimeException(e);
      }
    }
    for (InternalToolkitMap serverMap : this.serverMaps) {
      serverMap.registerAttributeExtractor(attrExtractor);
      ((ServerMap) serverMap).setSearchAttributeTypes(attrSchema.get());
    }
  }

  private static class PinnedEntryFaultCallbackImpl implements PinnedEntryFaultCallback {

    private final WeakReference<AggregateServerMap> serverMap;

    public PinnedEntryFaultCallbackImpl(AggregateServerMap serverMap) {
      this.serverMap = new WeakReference<AggregateServerMap>(serverMap);
    }

    @Override
    public void unlockedGet(Object key) {
      AggregateServerMap serverMapLocal = serverMap.get();
      if (serverMapLocal != null) {
        serverMapLocal.unlockedGet(key, true);
      }

    }

    @Override
    public void get(Object key) {
      AggregateServerMap serverMapLocal = serverMap.get();
      if (serverMapLocal != null) {
        serverMapLocal.get(key);
      }
    }
  }

  @Override
  public QueryBuilder createQueryBuilder() {
    return searchBuilderFactory.createQueryBuilder(this, getToolkitObjectType());
  }

  protected ToolkitObjectType getToolkitObjectType() {
    return this.toolkitObjectType;
  }

  @Override
  public Map<K, V> unlockedGetAll(Collection<K> keys, boolean quiet) {
    return Collections.unmodifiableMap(new GetAllCustomMap(keys, this, quiet, getAllBatchSize));
  }
}
