package org.apache.ignite.cache.database;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.annotations.QuerySqlField;
import org.apache.ignite.configuration.BinaryConfiguration;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.configuration.PersistenceConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
public class IgniteDbMultiNodePutGetRestartSelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    private File allocPath;

    /** */
    private static final int GRID_CNT = 3;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        MemoryConfiguration memCfg = new MemoryConfiguration();

        MemoryPolicyConfiguration memPlcCfg = new MemoryPolicyConfiguration();

        memPlcCfg.setName("dfltMemPlc");
        memPlcCfg.setSize(100 * 1024 * 1024);

        memCfg.setDefaultMemoryPolicyName("dfltMemPlc");
        memCfg.setMemoryPolicies(memPlcCfg);

        cfg.setMemoryConfiguration(memCfg);

        CacheConfiguration ccfg = new CacheConfiguration();

        ccfg.setIndexedTypes(Integer.class, DbValue.class);

        ccfg.setRebalanceMode(CacheRebalanceMode.NONE);

        ccfg.setAffinity(new RendezvousAffinityFunction(false, 32));

        ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        cfg.setCacheConfiguration(ccfg);

        PersistenceConfiguration dbCfg = new PersistenceConfiguration();

        cfg.setPersistenceConfiguration(dbCfg);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        cfg.setMarshaller(null);

        BinaryConfiguration bCfg = new BinaryConfiguration();

        bCfg.setCompactFooter(false);

        cfg.setBinaryConfiguration(bCfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));

        super.beforeTest();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
    }

    /**
     * @throws Exception if failed.
     */
    public void testPutGetSimple() throws Exception {
        String home = U.getIgniteHome();

        allocPath = new File(home, "work/db/" + UUID.randomUUID());

        allocPath.mkdirs();

        info(">>> Will use path: " + allocPath);

        startGrids(GRID_CNT);

        try {
            IgniteEx ig = grid(0);

            checkPutGetSql(ig, true);
        }
        finally {
            stopAllGrids();
        }

        info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");

        startGrids(GRID_CNT);

        try {
            IgniteEx ig = grid(0);

            checkPutGetSql(ig, false);
        }
        finally {
            stopAllGrids();
        }
    }

    private void checkPutGetSql(IgniteEx ig, boolean write) {
        IgniteCache<Integer, DbValue> cache = ig.cache(null);

        if (write) {
            try (IgniteDataStreamer<Object, Object> streamer = ig.dataStreamer(null)) {
                for (int i = 0; i < 10_000; i++)
                    streamer.addData(i, new DbValue(i, "value-" + i, i));
            }
        }

        List<List<?>> res = cache.query(new SqlFieldsQuery("select ival from dbvalue where ival < ? order by ival asc")
                .setArgs(10_000)).getAll();

        assertEquals(10_000, res.size());

        for (int i = 0; i < 10_000; i++) {
            assertEquals(1, res.get(i).size());
            assertEquals(i, res.get(i).get(0));
        }

        assertEquals(1, cache.query(new SqlFieldsQuery("select lval from dbvalue where ival = 7899")).getAll().size());
        assertEquals(5000, cache.query(new SqlFieldsQuery("select lval from dbvalue where ival >= 5000 and ival < 10000"))
                .getAll().size());

        for (int i = 0; i < 10_000; i++)
            assertEquals(new DbValue(i, "value-" + i, i), cache.get(i));
    }

    /**
     *
     */
    private static class DbValue implements Serializable {
        /** */
        @QuerySqlField(index = true)
        private int iVal;

        /** */
        @QuerySqlField(index = true)
        private String sVal;

        /** */
        @QuerySqlField
        private long lVal;

        /**
         * @param iVal Integer value.
         * @param sVal String value.
         * @param lVal Long value.
         */
        public DbValue(int iVal, String sVal, long lVal) {
            this.iVal = iVal;
            this.sVal = sVal;
            this.lVal = lVal;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            DbValue dbValue = (DbValue)o;

            return iVal == dbValue.iVal && lVal == dbValue.lVal &&
                !(sVal != null ? !sVal.equals(dbValue.sVal) : dbValue.sVal != null);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int res = iVal;

            res = 31 * res + (sVal != null ? sVal.hashCode() : 0);
            res = 31 * res + (int)(lVal ^ (lVal >>> 32));

            return res;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(DbValue.class, this);
        }
    }
}