package com.github.config.listener.zookeeper;

import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent.Type;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.ZKPaths;

import com.github.config.bus.ElasticConfigEvent;
import com.github.config.bus.ElasticConfigEventBus;
import com.github.config.bus.event.EventListener;
import com.github.config.group.ZookeeperElasticConfigGroup;
import com.github.config.listener.AbstractConfigListener;
import com.github.config.listener.AbstractListenerManager;
import com.github.config.listener.ElaticCofnigEventListener;

/**
 * 监听器管理
 * 
 * @author ZhangWei
 */
@Slf4j
@RequiredArgsConstructor
public class ZookeeperListenerManager extends AbstractListenerManager {

    private final ZookeeperElasticConfigGroup zookeeperConfigGroup;

    private final Object lockObject = ZookeeperListenerManager.class;

    @Override
    public void start() {
        addDataListener(new ConfigChangedConfigListener());
        addConnectionStateListener(new ConnectionLostListener());
        addEventListenerStateListener(new ElaticCofnigEventListener(zookeeperConfigGroup));
    }

    /**
     * 添加事件监听器.
     */
    class ConfigChangedConfigListener extends AbstractConfigListener {

        @Override
        protected void dataChanged(final CuratorFramework client, final TreeCacheEvent event, final String path) {

            if (!isStopped(client) && isNotified(client, event, path)) {
                loadKeyAndPushEvent(event, path);
            }
        }
    }

    /**
     * 连接状态监听器.
     */
    class ConnectionLostListener implements ConnectionStateListener {

        @Override
        public void stateChanged(final CuratorFramework client, final ConnectionState newState) {
            log.info("zookeeper connection state changed.new state:{}", newState);
            if (ConnectionState.RECONNECTED == newState) {
                zookeeperConfigGroup.loadNode();
            }
        }
    }

    /**
     * 添加数据结点监听器.
     */
    private void addDataListener(TreeCacheListener listener) {
        zookeeperConfigGroup.getConfigNodeStorage().addDataListener(listener);
    }

    /**
     * 添加连接状态监听器.
     */
    private void addConnectionStateListener(ConnectionStateListener listener) {
        zookeeperConfigGroup.getConfigNodeStorage().addConnectionStateListener(listener);
    }

    /**
     * 添加事件监听器.
     */
    private void addEventListenerStateListener(EventListener listener) {
        listener.register();
    }

    private String oldvalue(final String path) {
        return zookeeperConfigGroup.get(ZKPaths.getNodeFromPath(path));
    }

    /**
     * 重加载路径并发布事件
     * 
     * @param event 配置节点变更事件
     * @param path 配置节点路径
     */
    @Synchronized("lockObject")
    private void loadKeyAndPushEvent(final TreeCacheEvent event, final String path) {

        String oldvalue = oldvalue(path);
        String value = zookeeperConfigGroup.getConfigNodeStorage().getConfigNodeDataDirectly(
            ZKPaths.getNodeFromPath(path));
        if (!StringUtils.equals(value, oldvalue)) {
            ZookeeperListenerManager.this.reload(path);
            ZookeeperListenerManager.this.pushEvent(event, path, value);
        }
    }

    /**
     * 重加载路径path配置节点
     * 
     * @param path 配置节点路径
     */
    private void reload(String path) {
        log.debug("reload the config node:{}", ZKPaths.getNodeFromPath(path));
        String key = ZKPaths.getNodeFromPath(path);
        zookeeperConfigGroup.reloadKey(key);
    }

    /**
     * 发布事件
     * 
     * @param event 配置节点变更事件
     * @param path 配置节点路径
     * @param value 配置节点新值
     * @param oldvalue 配置节点原始值
     */
    private void pushEvent(final TreeCacheEvent event, String path, String value) {

        if (event.getType() == Type.NODE_UPDATED) {
            ElasticConfigEventBus.pushEvent(ElasticConfigEvent.builder().path(path).value(value)
                .eventType(eventMap.get(event.getType())).build());
        }
    }

    /**
     * 判断Zookeeper客户端是是否已径关闭
     * 
     * @param client Zookeeper客户端
     * @return 是否已径关闭
     */
    private boolean isStopped(final CuratorFramework client) {
        return client.getState() == CuratorFrameworkState.STOPPED || client.getState() == CuratorFrameworkState.LATENT;
    }

    /**
     * 判断配置节点变更时是否需要发送消息知通
     * 
     * @param client zookeeper客户端
     * @param event 配置节点变更事件
     * @param path 配置节点路径
     * @return 是否通知
     */
    private boolean isNotified(final CuratorFramework client, final TreeCacheEvent event, final String path) {
        log.debug("elastic config node change.type:{},path:{}", event.getType(), path);
        return zookeeperConfigGroup.getConfigNodeStorage().getConfigProfile()
            .getFullPath(ZKPaths.getNodeFromPath(path)).equals(path)
            && (eventMap.containsKey(event.getType()));
    }
}
