/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.coordination;

import org.apache.logging.log4j.Logger;
import org.opensearch.LegacyESVersion;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateTaskExecutor;
import org.opensearch.cluster.NotClusterManagerException;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.decommission.DecommissionAttribute;
import org.opensearch.cluster.decommission.DecommissionAttributeMetadata;
import org.opensearch.cluster.decommission.DecommissionStatus;
import org.opensearch.cluster.decommission.NodeDecommissionedException;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RerouteService;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.common.Priority;
import org.opensearch.common.settings.Settings;
import org.opensearch.persistent.PersistentTasksCustomMetadata;
import org.opensearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.opensearch.gateway.GatewayService.STATE_NOT_RECOVERED_BLOCK;

/**
 * Main executor for Nodes joining the OpenSearch cluster
 *
 * @opensearch.internal
 */
public class JoinTaskExecutor implements ClusterStateTaskExecutor<JoinTaskExecutor.Task> {

    private final AllocationService allocationService;

    private final Logger logger;
    private final RerouteService rerouteService;
    private final TransportService transportService;

    /**
     * Task for the join task executor.
     *
     * @opensearch.internal
     */
    public static class Task {

        private final DiscoveryNode node;
        private final String reason;

        public Task(DiscoveryNode node, String reason) {
            this.node = node;
            this.reason = reason;
        }

        public DiscoveryNode node() {
            return node;
        }

        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return node != null ? node + " " + reason : reason;
        }

        public boolean isBecomeClusterManagerTask() {
            return reason.equals(BECOME_MASTER_TASK_REASON) || reason.equals(BECOME_CLUSTER_MANAGER_TASK_REASON);
        }

        /** @deprecated As of 2.2, because supporting inclusive language, replaced by {@link #isBecomeClusterManagerTask()} */
        @Deprecated
        public boolean isBecomeMasterTask() {
            return isBecomeClusterManagerTask();
        }

        public boolean isFinishElectionTask() {
            return reason.equals(FINISH_ELECTION_TASK_REASON);
        }

        /**
         * @deprecated As of 2.0, because supporting inclusive language, replaced by {@link #BECOME_CLUSTER_MANAGER_TASK_REASON}
         */
        @Deprecated
        private static final String BECOME_MASTER_TASK_REASON = "_BECOME_MASTER_TASK_";
        private static final String BECOME_CLUSTER_MANAGER_TASK_REASON = "_BECOME_CLUSTER_MANAGER_TASK_";
        private static final String FINISH_ELECTION_TASK_REASON = "_FINISH_ELECTION_";
    }

    public JoinTaskExecutor(
        Settings settings,
        AllocationService allocationService,
        Logger logger,
        RerouteService rerouteService,
        TransportService transportService
    ) {
        this.allocationService = allocationService;
        this.logger = logger;
        this.rerouteService = rerouteService;
        this.transportService = transportService;
    }

    @Override
    public ClusterTasksResult<Task> execute(ClusterState currentState, List<Task> joiningNodes) throws Exception {
        final ClusterTasksResult.Builder<Task> results = ClusterTasksResult.builder();

        final DiscoveryNodes currentNodes = currentState.nodes();
        boolean nodesChanged = false;
        ClusterState.Builder newState;

        if (joiningNodes.size() == 1 && joiningNodes.get(0).isFinishElectionTask()) {
            return results.successes(joiningNodes).build(currentState);
        } else if (currentNodes.getClusterManagerNode() == null && joiningNodes.stream().anyMatch(Task::isBecomeClusterManagerTask)) {
            assert joiningNodes.stream().anyMatch(Task::isFinishElectionTask) : "becoming a cluster-manager but election is not finished "
                + joiningNodes;
            // use these joins to try and become the cluster-manager.
            // Note that we don't have to do any validation of the amount of joining nodes - the commit
            // during the cluster state publishing guarantees that we have enough
            newState = becomeClusterManagerAndTrimConflictingNodes(currentState, joiningNodes);
            nodesChanged = true;
        } else if (currentNodes.isLocalNodeElectedClusterManager() == false) {
            logger.trace(
                "processing node joins, but we are not the cluster-manager. current cluster-manager: {}",
                currentNodes.getClusterManagerNode()
            );
            throw new NotClusterManagerException("Node [" + currentNodes.getLocalNode() + "] not cluster-manager for join request");
        } else {
            newState = ClusterState.builder(currentState);
        }

        DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder(newState.nodes());

        assert nodesBuilder.isLocalNodeElectedClusterManager();

        Version minClusterNodeVersion = newState.nodes().getMinNodeVersion();
        Version maxClusterNodeVersion = newState.nodes().getMaxNodeVersion();
        // we only enforce major version transitions on a fully formed clusters
        final boolean enforceMajorVersion = currentState.getBlocks().hasGlobalBlock(STATE_NOT_RECOVERED_BLOCK) == false;
        // processing any joins
        Map<String, String> joiniedNodeNameIds = new HashMap<>();
        for (final Task joinTask : joiningNodes) {
            if (joinTask.isBecomeClusterManagerTask() || joinTask.isFinishElectionTask()) {
                // noop
            } else if (currentNodes.nodeExistsWithSameRoles(joinTask.node()) && !currentNodes.nodeExistsWithBWCVersion(joinTask.node())) {
                logger.debug("received a join request for an existing node [{}]", joinTask.node());
            } else {
                final DiscoveryNode node = joinTask.node();
                try {
                    if (enforceMajorVersion) {
                        ensureMajorVersionBarrier(node.getVersion(), minClusterNodeVersion);
                    }
                    ensureNodesCompatibility(node.getVersion(), minClusterNodeVersion, maxClusterNodeVersion);
                    // we do this validation quite late to prevent race conditions between nodes joining and importing dangling indices
                    // we have to reject nodes that don't support all indices we have in this cluster
                    ensureIndexCompatibility(node.getVersion(), currentState.getMetadata());
                    nodesBuilder.add(node);
                    nodesChanged = true;
                    minClusterNodeVersion = Version.min(minClusterNodeVersion, node.getVersion());
                    maxClusterNodeVersion = Version.max(maxClusterNodeVersion, node.getVersion());
                    if (node.isClusterManagerNode()) {
                        joiniedNodeNameIds.put(node.getName(), node.getId());
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    results.failure(joinTask, e);
                    continue;
                }
            }
            results.success(joinTask);
        }

        if (nodesChanged) {
            rerouteService.reroute(
                "post-join reroute",
                Priority.HIGH,
                ActionListener.wrap(r -> logger.trace("post-join reroute completed"), e -> logger.debug("post-join reroute failed", e))
            );

            if (joiniedNodeNameIds.isEmpty() == false) {
                Set<CoordinationMetadata.VotingConfigExclusion> currentVotingConfigExclusions = currentState.getVotingConfigExclusions();
                Set<CoordinationMetadata.VotingConfigExclusion> newVotingConfigExclusions = currentVotingConfigExclusions.stream()
                    .map(e -> {
                        // Update nodeId in VotingConfigExclusion when a new node with excluded node name joins
                        if (CoordinationMetadata.VotingConfigExclusion.MISSING_VALUE_MARKER.equals(e.getNodeId())
                            && joiniedNodeNameIds.containsKey(e.getNodeName())) {
                            return new CoordinationMetadata.VotingConfigExclusion(joiniedNodeNameIds.get(e.getNodeName()), e.getNodeName());
                        } else {
                            return e;
                        }
                    })
                    .collect(Collectors.toSet());

                // if VotingConfigExclusions did get updated
                if (newVotingConfigExclusions.equals(currentVotingConfigExclusions) == false) {
                    CoordinationMetadata.Builder coordMetadataBuilder = CoordinationMetadata.builder(currentState.coordinationMetadata())
                        .clearVotingConfigExclusions();
                    newVotingConfigExclusions.forEach(coordMetadataBuilder::addVotingConfigExclusion);
                    Metadata newMetadata = Metadata.builder(currentState.metadata())
                        .coordinationMetadata(coordMetadataBuilder.build())
                        .build();
                    return results.build(
                        allocationService.adaptAutoExpandReplicas(newState.nodes(nodesBuilder).metadata(newMetadata).build())
                    );
                }
            }

            return results.build(allocationService.adaptAutoExpandReplicas(newState.nodes(nodesBuilder).build()));
        } else {
            // we must return a new cluster state instance to force publishing. This is important
            // for the joining node to finalize its join and set us as a cluster-manager
            return results.build(newState.build());
        }
    }

    protected ClusterState.Builder becomeClusterManagerAndTrimConflictingNodes(ClusterState currentState, List<Task> joiningNodes) {
        assert currentState.nodes().getClusterManagerNodeId() == null : currentState;
        DiscoveryNodes currentNodes = currentState.nodes();
        DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder(currentNodes);
        nodesBuilder.clusterManagerNodeId(currentState.nodes().getLocalNodeId());

        for (final Task joinTask : joiningNodes) {
            if (joinTask.isBecomeClusterManagerTask()) {
                refreshDiscoveryNodeVersionAfterUpgrade(currentNodes, nodesBuilder);
            } else if (joinTask.isFinishElectionTask()) {
                // no-op
            } else {
                final DiscoveryNode joiningNode = joinTask.node();
                final DiscoveryNode nodeWithSameId = nodesBuilder.get(joiningNode.getId());
                if (nodeWithSameId != null && nodeWithSameId.equals(joiningNode) == false) {
                    logger.debug("removing existing node [{}], which conflicts with incoming join from [{}]", nodeWithSameId, joiningNode);
                    nodesBuilder.remove(nodeWithSameId.getId());
                }
                final DiscoveryNode nodeWithSameAddress = currentNodes.findByAddress(joiningNode.getAddress());
                if (nodeWithSameAddress != null && nodeWithSameAddress.equals(joiningNode) == false) {
                    logger.debug(
                        "removing existing node [{}], which conflicts with incoming join from [{}]",
                        nodeWithSameAddress,
                        joiningNode
                    );
                    nodesBuilder.remove(nodeWithSameAddress.getId());
                }
            }
        }

        // now trim any left over dead nodes - either left there when the previous cluster-manager stepped down
        // or removed by us above
        ClusterState tmpState = ClusterState.builder(currentState)
            .nodes(nodesBuilder)
            .blocks(
                ClusterBlocks.builder()
                    .blocks(currentState.blocks())
                    .removeGlobalBlock(NoClusterManagerBlockService.NO_CLUSTER_MANAGER_BLOCK_ID)
            )
            .build();
        logger.trace("becomeClusterManagerAndTrimConflictingNodes: {}", tmpState.nodes());
        allocationService.cleanCaches();
        tmpState = PersistentTasksCustomMetadata.disassociateDeadNodes(tmpState);
        return ClusterState.builder(allocationService.disassociateDeadNodes(tmpState, false, "removed dead nodes on election"));
    }

    private void refreshDiscoveryNodeVersionAfterUpgrade(DiscoveryNodes currentNodes, DiscoveryNodes.Builder nodesBuilder) {
        // During the upgrade from Elasticsearch, OpenSearch node send their version as 7.10.2 to Elasticsearch master
        // in order to successfully join the cluster. But as soon as OpenSearch node becomes the master, cluster state
        // should show the OpenSearch nodes version as 1.x. As the cluster state was carry forwarded from ES master,
        // version in DiscoveryNode is stale 7.10.2. As soon as OpenSearch node becomes master, it can refresh the
        // DiscoveryNodes version and publish the updated state while finishing the election. This helps in atomically
        // updating the version of those node which have connection with the new master.
        // Note: This should get deprecated with BWC mode logic
        if (null == transportService) {
            // this logic is only applicable when OpenSearch node is cluster-manager and is noop for zen discovery node
            return;
        }
        if (currentNodes.getMinNodeVersion().before(Version.V_1_0_0)) {
            Map<String, Version> channelVersions = transportService.getChannelVersion(currentNodes);
            for (DiscoveryNode node : currentNodes) {
                if (channelVersions.containsKey(node.getId())) {
                    if (channelVersions.get(node.getId()) != node.getVersion()) {
                        DiscoveryNode tmpNode = nodesBuilder.get(node.getId());
                        nodesBuilder.remove(node.getId());
                        nodesBuilder.add(
                            new DiscoveryNode(
                                tmpNode.getName(),
                                tmpNode.getId(),
                                tmpNode.getEphemeralId(),
                                tmpNode.getHostName(),
                                tmpNode.getHostAddress(),
                                tmpNode.getAddress(),
                                tmpNode.getAttributes(),
                                tmpNode.getRoles(),
                                channelVersions.get(tmpNode.getId())
                            )
                        );
                        logger.info(
                            "Refreshed the DiscoveryNode version for node {}:{} from {} to {}",
                            node.getId(),
                            node.getAddress(),
                            node.getVersion(),
                            channelVersions.get(tmpNode.getId())
                        );
                    }
                } else {
                    // in case existing OpenSearch node is present in the cluster and but there is no connection to that node yet,
                    // either that node will send new JoinRequest to the cluster-manager/master with version >=1.0, then no issue or
                    // there is an edge case if doesn't send JoinRequest and connection is established,
                    // then it can continue to report version as 7.10.2 instead of actual OpenSearch version. So,
                    // removing the node from cluster state to prevent stale version reporting and let it reconnect.
                    if (node.getVersion().equals(LegacyESVersion.V_7_10_2)) {
                        nodesBuilder.remove(node.getId());
                    }
                }
            }
        }
    }

    @Override
    public boolean runOnlyOnClusterManager() {
        // we validate that we are allowed to change the cluster state during cluster state processing
        return false;
    }

    /**
     * a task indicates that the current node should become master
     *
     * @deprecated As of 2.0, because supporting inclusive language, replaced by {@link #newBecomeClusterManagerTask()}
     */
    @Deprecated
    public static Task newBecomeMasterTask() {
        return new Task(null, Task.BECOME_MASTER_TASK_REASON);
    }

    /**
     * a task indicates that the current node should become cluster-manager
     */
    public static Task newBecomeClusterManagerTask() {
        return new Task(null, Task.BECOME_CLUSTER_MANAGER_TASK_REASON);
    }

    /**
     * a task that is used to signal the election is stopped and we should process pending joins.
     * it may be used in combination with {@link JoinTaskExecutor#newBecomeClusterManagerTask()}
     */
    public static Task newFinishElectionTask() {
        return new Task(null, Task.FINISH_ELECTION_TASK_REASON);
    }

    /**
     * Ensures that all indices are compatible with the given node version. This will ensure that all indices in the given metadata
     * will not be created with a newer version of opensearch as well as that all indices are newer or equal to the minimum index
     * compatibility version.
     *
     * @throws IllegalStateException if any index is incompatible with the given version
     * @see Version#minimumIndexCompatibilityVersion()
     */
    public static void ensureIndexCompatibility(final Version nodeVersion, Metadata metadata) {
        Version supportedIndexVersion = nodeVersion.minimumIndexCompatibilityVersion();
        // we ensure that all indices in the cluster we join are compatible with us no matter if they are
        // closed or not we can't read mappings of these indices so we need to reject the join...
        for (IndexMetadata idxMetadata : metadata) {
            if (idxMetadata.getCreationVersion().after(nodeVersion)) {
                throw new IllegalStateException(
                    "index "
                        + idxMetadata.getIndex()
                        + " version not supported: "
                        + idxMetadata.getCreationVersion()
                        + " the node version is: "
                        + nodeVersion
                );
            }
            if (idxMetadata.getCreationVersion().before(supportedIndexVersion)) {
                throw new IllegalStateException(
                    "index "
                        + idxMetadata.getIndex()
                        + " version not supported: "
                        + idxMetadata.getCreationVersion()
                        + " minimum compatible index version is: "
                        + supportedIndexVersion
                );
            }
        }
    }

    /**
     * ensures that the joining node has a version that's compatible with all current nodes
     */
    public static void ensureNodesCompatibility(final Version joiningNodeVersion, DiscoveryNodes currentNodes) {
        final Version minNodeVersion = currentNodes.getMinNodeVersion();
        final Version maxNodeVersion = currentNodes.getMaxNodeVersion();
        ensureNodesCompatibility(joiningNodeVersion, minNodeVersion, maxNodeVersion);
    }

    /**
     * ensures that the joining node has a version that's compatible with a given version range
     */
    public static void ensureNodesCompatibility(Version joiningNodeVersion, Version minClusterNodeVersion, Version maxClusterNodeVersion) {
        assert minClusterNodeVersion.onOrBefore(maxClusterNodeVersion) : minClusterNodeVersion + " > " + maxClusterNodeVersion;
        if (joiningNodeVersion.isCompatible(maxClusterNodeVersion) == false) {
            throw new IllegalStateException(
                "node version ["
                    + joiningNodeVersion
                    + "] is not supported. "
                    + "The cluster contains nodes with version ["
                    + maxClusterNodeVersion
                    + "], which is incompatible."
            );
        }
        if (joiningNodeVersion.isCompatible(minClusterNodeVersion) == false) {
            throw new IllegalStateException(
                "node version ["
                    + joiningNodeVersion
                    + "] is not supported."
                    + "The cluster contains nodes with version ["
                    + minClusterNodeVersion
                    + "], which is incompatible."
            );
        }
    }

    /**
     * ensures that the joining node's major version is equal or higher to the minClusterNodeVersion. This is needed
     * to ensure that if the cluster-manager/master is already fully operating under the new major version, it doesn't go back to mixed
     * version mode
     **/
    public static void ensureMajorVersionBarrier(Version joiningNodeVersion, Version minClusterNodeVersion) {
        final byte clusterMajor = minClusterNodeVersion.major == 1 ? 7 : minClusterNodeVersion.major;
        if (joiningNodeVersion.compareMajor(minClusterNodeVersion) < 0) {
            throw new IllegalStateException(
                "node version ["
                    + joiningNodeVersion
                    + "] is not supported. "
                    + "All nodes in the cluster are of a higher major ["
                    + clusterMajor
                    + "]."
            );
        }
    }

    public static void ensureNodeCommissioned(DiscoveryNode node, Metadata metadata) {
        DecommissionAttributeMetadata decommissionAttributeMetadata = metadata.decommissionAttributeMetadata();
        if (decommissionAttributeMetadata != null) {
            DecommissionAttribute decommissionAttribute = decommissionAttributeMetadata.decommissionAttribute();
            DecommissionStatus status = decommissionAttributeMetadata.status();
            if (decommissionAttribute != null && status != null) {
                // We will let the node join the cluster if the current status is in FAILED state
                if (node.getAttributes().get(decommissionAttribute.attributeName()).equals(decommissionAttribute.attributeValue())
                    && (status.equals(DecommissionStatus.IN_PROGRESS) || status.equals(DecommissionStatus.SUCCESSFUL))) {
                    throw new NodeDecommissionedException(
                        "node [{}] has decommissioned attribute [{}] with current status of decommissioning [{}]",
                        node.toString(),
                        decommissionAttribute.toString(),
                        status.status()
                    );
                }
            }
        }
    }

    public static Collection<BiConsumer<DiscoveryNode, ClusterState>> addBuiltInJoinValidators(
        Collection<BiConsumer<DiscoveryNode, ClusterState>> onJoinValidators
    ) {
        final Collection<BiConsumer<DiscoveryNode, ClusterState>> validators = new ArrayList<>();
        validators.add((node, state) -> {
            ensureNodesCompatibility(node.getVersion(), state.getNodes());
            ensureIndexCompatibility(node.getVersion(), state.getMetadata());
            ensureNodeCommissioned(node, state.getMetadata());
        });
        validators.addAll(onJoinValidators);
        return Collections.unmodifiableCollection(validators);
    }
}
