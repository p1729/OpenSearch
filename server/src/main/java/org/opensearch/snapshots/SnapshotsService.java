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
 *    http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.snapshots;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.ExceptionsHelper;
import org.opensearch.LegacyESVersion;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionRunnable;
import org.opensearch.action.StepListener;
import org.opensearch.action.admin.cluster.snapshots.clone.CloneSnapshotRequest;
import org.opensearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest;
import org.opensearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.action.support.clustermanager.TransportClusterManagerNodeAction;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateApplier;
import org.opensearch.cluster.ClusterStateTaskConfig;
import org.opensearch.cluster.ClusterStateTaskExecutor;
import org.opensearch.cluster.ClusterStateTaskListener;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.NotClusterManagerException;
import org.opensearch.cluster.RepositoryCleanupInProgress;
import org.opensearch.cluster.RestoreInProgress;
import org.opensearch.cluster.SnapshotDeletionsInProgress;
import org.opensearch.cluster.SnapshotsInProgress;
import org.opensearch.cluster.SnapshotsInProgress.ShardSnapshotStatus;
import org.opensearch.cluster.SnapshotsInProgress.ShardState;
import org.opensearch.cluster.SnapshotsInProgress.State;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.coordination.FailedToCommitClusterStateException;
import org.opensearch.cluster.metadata.DataStream;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.metadata.RepositoriesMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.Priority;
import org.opensearch.common.Strings;
import org.opensearch.common.UUIDs;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.component.AbstractLifecycleComponent;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.regex.Regex;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.index.Index;
import org.opensearch.index.shard.ShardId;
import org.opensearch.repositories.IndexId;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.repositories.Repository;
import org.opensearch.repositories.RepositoryData;
import org.opensearch.repositories.RepositoryException;
import org.opensearch.repositories.RepositoryMissingException;
import org.opensearch.repositories.RepositoryShardId;
import org.opensearch.repositories.ShardGenerations;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableList;
import static org.opensearch.cluster.SnapshotsInProgress.completed;

/**
 * Service responsible for creating snapshots. This service runs all the steps executed on the cluster-manager node during snapshot creation and
 * deletion.
 * See package level documentation of {@link org.opensearch.snapshots} for details.
 *
 * @opensearch.internal
 */
public class SnapshotsService extends AbstractLifecycleComponent implements ClusterStateApplier {

    /**
     * Minimum node version which does not use {@link Repository#initializeSnapshot(SnapshotId, List, Metadata)} to write snapshot metadata
     * when starting a snapshot.
     */
    public static final Version NO_REPO_INITIALIZE_VERSION = LegacyESVersion.V_7_5_0;

    public static final Version FULL_CONCURRENCY_VERSION = LegacyESVersion.V_7_9_0;

    public static final Version CLONE_SNAPSHOT_VERSION = LegacyESVersion.V_7_10_0;

    public static final Version SHARD_GEN_IN_REPO_DATA_VERSION = LegacyESVersion.V_7_6_0;

    public static final Version INDEX_GEN_IN_REPO_DATA_VERSION = LegacyESVersion.V_7_9_0;

    public static final Version OLD_SNAPSHOT_FORMAT = LegacyESVersion.V_7_5_0;

    public static final Version MULTI_DELETE_VERSION = LegacyESVersion.V_7_8_0;

    private static final Logger logger = LogManager.getLogger(SnapshotsService.class);

    public static final String UPDATE_SNAPSHOT_STATUS_ACTION_NAME = "internal:cluster/snapshot/update_snapshot_status";

    private final ClusterService clusterService;

    private final IndexNameExpressionResolver indexNameExpressionResolver;

    private final RepositoriesService repositoriesService;

    private final ThreadPool threadPool;

    private final Map<Snapshot, List<ActionListener<Tuple<RepositoryData, SnapshotInfo>>>> snapshotCompletionListeners =
        new ConcurrentHashMap<>();

    // Set of snapshots that are currently being initialized by this node
    private final Set<Snapshot> initializingSnapshots = Collections.synchronizedSet(new HashSet<>());

    /**
     * Listeners for snapshot deletion keyed by delete uuid as returned from {@link SnapshotDeletionsInProgress.Entry#uuid()}
     */
    private final Map<String, List<ActionListener<Void>>> snapshotDeletionListeners = new HashMap<>();

    // Set of repositories currently running either a snapshot finalization or a snapshot delete.
    private final Set<String> currentlyFinalizing = Collections.synchronizedSet(new HashSet<>());

    // Set of snapshots that are currently being ended by this node
    private final Set<Snapshot> endingSnapshots = Collections.synchronizedSet(new HashSet<>());

    // Set of currently initializing clone operations
    private final Set<Snapshot> initializingClones = Collections.synchronizedSet(new HashSet<>());

    private final UpdateSnapshotStatusAction updateSnapshotStatusHandler;

    private final TransportService transportService;

    private final OngoingRepositoryOperations repositoryOperations = new OngoingRepositoryOperations();

    /**
     * Setting that specifies the maximum number of allowed concurrent snapshot create and delete operations in the
     * cluster state. The number of concurrent operations in a cluster state is defined as the sum of the sizes of
     * {@link SnapshotsInProgress#entries()} and {@link SnapshotDeletionsInProgress#getEntries()}.
     */
    public static final Setting<Integer> MAX_CONCURRENT_SNAPSHOT_OPERATIONS_SETTING = Setting.intSetting(
        "snapshot.max_concurrent_operations",
        1000,
        1,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    private volatile int maxConcurrentOperations;

    public SnapshotsService(
        Settings settings,
        ClusterService clusterService,
        IndexNameExpressionResolver indexNameExpressionResolver,
        RepositoriesService repositoriesService,
        TransportService transportService,
        ActionFilters actionFilters
    ) {
        this.clusterService = clusterService;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.repositoriesService = repositoriesService;
        this.threadPool = transportService.getThreadPool();
        this.transportService = transportService;

        // The constructor of UpdateSnapshotStatusAction will register itself to the TransportService.
        this.updateSnapshotStatusHandler = new UpdateSnapshotStatusAction(
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            indexNameExpressionResolver
        );
        if (DiscoveryNode.isClusterManagerNode(settings)) {
            // addLowPriorityApplier to make sure that Repository will be created before snapshot
            clusterService.addLowPriorityApplier(this);
            maxConcurrentOperations = MAX_CONCURRENT_SNAPSHOT_OPERATIONS_SETTING.get(settings);
            clusterService.getClusterSettings()
                .addSettingsUpdateConsumer(MAX_CONCURRENT_SNAPSHOT_OPERATIONS_SETTING, i -> maxConcurrentOperations = i);
        }
    }

    /**
     * Same as {@link #createSnapshot(CreateSnapshotRequest, ActionListener)} but invokes its callback on completion of
     * the snapshot.
     * Note: This method is only used in clusters that contain a node older than {@link #NO_REPO_INITIALIZE_VERSION} to ensure a backwards
     * compatible path for initializing the snapshot in the repository is executed.
     *
     * @param request snapshot request
     * @param listener snapshot completion listener
     */
    public void executeSnapshotLegacy(final CreateSnapshotRequest request, final ActionListener<SnapshotInfo> listener) {
        createSnapshotLegacy(
            request,
            ActionListener.wrap(snapshot -> addListener(snapshot, ActionListener.map(listener, Tuple::v2)), listener::onFailure)
        );
    }

    /**
     * Initializes the snapshotting process.
     * <p>
     * This method is used by clients to start snapshot. It makes sure that there is no snapshots are currently running and
     * creates a snapshot record in cluster state metadata.
     * Note: This method is only used in clusters that contain a node older than {@link #NO_REPO_INITIALIZE_VERSION} to ensure a backwards
     * compatible path for initializing the snapshot in the repository is executed.
     *
     * @param request  snapshot request
     * @param listener snapshot creation listener
     */
    public void createSnapshotLegacy(final CreateSnapshotRequest request, final ActionListener<Snapshot> listener) {
        final String repositoryName = request.repository();
        final String snapshotName = indexNameExpressionResolver.resolveDateMathExpression(request.snapshot());
        validate(repositoryName, snapshotName);
        final SnapshotId snapshotId = new SnapshotId(snapshotName, UUIDs.randomBase64UUID()); // new UUID for the snapshot
        Repository repository = repositoriesService.repository(request.repository());
        final Map<String, Object> userMeta = repository.adaptUserMetadata(request.userMetadata());
        clusterService.submitStateUpdateTask("create_snapshot [" + snapshotName + ']', new ClusterStateUpdateTask() {

            private List<String> indices;

            private SnapshotsInProgress.Entry newEntry;

            @Override
            public ClusterState execute(ClusterState currentState) {
                validate(repositoryName, snapshotName, currentState);
                SnapshotDeletionsInProgress deletionsInProgress = currentState.custom(SnapshotDeletionsInProgress.TYPE);
                if (deletionsInProgress != null && deletionsInProgress.hasDeletionsInProgress()) {
                    throw new ConcurrentSnapshotExecutionException(
                        repositoryName,
                        snapshotName,
                        "cannot snapshot while a snapshot deletion is in-progress in [" + deletionsInProgress + "]"
                    );
                }
                final RepositoryCleanupInProgress repositoryCleanupInProgress = currentState.custom(RepositoryCleanupInProgress.TYPE);
                if (repositoryCleanupInProgress != null && repositoryCleanupInProgress.hasCleanupInProgress()) {
                    throw new ConcurrentSnapshotExecutionException(
                        repositoryName,
                        snapshotName,
                        "cannot snapshot while a repository cleanup is in-progress in [" + repositoryCleanupInProgress + "]"
                    );
                }
                SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE);
                // Fail if there are any concurrently running snapshots. The only exception to this being a snapshot in INIT state from a
                // previous cluster-manager that we can simply ignore and remove from the cluster state because we would clean it up from
                // the cluster state anyway in #applyClusterState.
                if (snapshots != null
                    && snapshots.entries()
                        .stream()
                        .anyMatch(
                            entry -> (entry.state() == State.INIT && initializingSnapshots.contains(entry.snapshot()) == false) == false
                        )) {
                    throw new ConcurrentSnapshotExecutionException(repositoryName, snapshotName, " a snapshot is already running");
                }
                // Store newSnapshot here to be processed in clusterStateProcessed
                indices = Arrays.asList(indexNameExpressionResolver.concreteIndexNames(currentState, request));

                final List<String> dataStreams = indexNameExpressionResolver.dataStreamNames(
                    currentState,
                    request.indicesOptions(),
                    request.indices()
                );

                logger.trace("[{}][{}] creating snapshot for indices [{}]", repositoryName, snapshotName, indices);
                newEntry = new SnapshotsInProgress.Entry(
                    new Snapshot(repositoryName, snapshotId),
                    request.includeGlobalState(),
                    request.partial(),
                    State.INIT,
                    Collections.emptyList(), // We'll resolve the list of indices when moving to the STARTED state in #beginSnapshot
                    dataStreams,
                    threadPool.absoluteTimeInMillis(),
                    RepositoryData.UNKNOWN_REPO_GEN,
                    ImmutableOpenMap.of(),
                    userMeta,
                    Version.CURRENT
                );
                initializingSnapshots.add(newEntry.snapshot());
                snapshots = SnapshotsInProgress.of(Collections.singletonList(newEntry));
                return ClusterState.builder(currentState).putCustom(SnapshotsInProgress.TYPE, snapshots).build();
            }

            @Override
            public void onFailure(String source, Exception e) {
                logger.warn(() -> new ParameterizedMessage("[{}][{}] failed to create snapshot", repositoryName, snapshotName), e);
                if (newEntry != null) {
                    initializingSnapshots.remove(newEntry.snapshot());
                }
                newEntry = null;
                listener.onFailure(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, final ClusterState newState) {
                if (newEntry != null) {
                    final Snapshot current = newEntry.snapshot();
                    assert initializingSnapshots.contains(current);
                    assert indices != null;
                    beginSnapshot(newState, newEntry, request.partial(), indices, repository, new ActionListener<Snapshot>() {
                        @Override
                        public void onResponse(final Snapshot snapshot) {
                            initializingSnapshots.remove(snapshot);
                            listener.onResponse(snapshot);
                        }

                        @Override
                        public void onFailure(final Exception e) {
                            initializingSnapshots.remove(current);
                            listener.onFailure(e);
                        }
                    });
                }
            }

            @Override
            public TimeValue timeout() {
                return request.clusterManagerNodeTimeout();
            }
        });
    }

    /**
     * Same as {@link #createSnapshot(CreateSnapshotRequest, ActionListener)} but invokes its callback on completion of
     * the snapshot.
     *
     * @param request snapshot request
     * @param listener snapshot completion listener
     */
    public void executeSnapshot(final CreateSnapshotRequest request, final ActionListener<SnapshotInfo> listener) {
        createSnapshot(
            request,
            ActionListener.wrap(snapshot -> addListener(snapshot, ActionListener.map(listener, Tuple::v2)), listener::onFailure)
        );
    }

    /**
     * Initializes the snapshotting process.
     * <p>
     * This method is used by clients to start snapshot. It makes sure that there is no snapshots are currently running and
     * creates a snapshot record in cluster state metadata.
     *
     * @param request  snapshot request
     * @param listener snapshot creation listener
     */
    public void createSnapshot(final CreateSnapshotRequest request, final ActionListener<Snapshot> listener) {
        final String repositoryName = request.repository();
        final String snapshotName = indexNameExpressionResolver.resolveDateMathExpression(request.snapshot());
        validate(repositoryName, snapshotName);
        // TODO: create snapshot UUID in CreateSnapshotRequest and make this operation idempotent to cleanly deal with transport layer
        // retries
        final SnapshotId snapshotId = new SnapshotId(snapshotName, UUIDs.randomBase64UUID()); // new UUID for the snapshot
        Repository repository = repositoriesService.repository(request.repository());
        if (repository.isReadOnly()) {
            listener.onFailure(new RepositoryException(repository.getMetadata().name(), "cannot create snapshot in a readonly repository"));
            return;
        }
        final Snapshot snapshot = new Snapshot(repositoryName, snapshotId);
        final Map<String, Object> userMeta = repository.adaptUserMetadata(request.userMetadata());
        repository.executeConsistentStateUpdate(repositoryData -> new ClusterStateUpdateTask() {

            private SnapshotsInProgress.Entry newEntry;

            @Override
            public ClusterState execute(ClusterState currentState) {
                ensureSnapshotNameAvailableInRepo(repositoryData, snapshotName, repository);
                final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                final List<SnapshotsInProgress.Entry> runningSnapshots = snapshots.entries();
                ensureSnapshotNameNotRunning(runningSnapshots, repositoryName, snapshotName);
                validate(repositoryName, snapshotName, currentState);
                final boolean concurrentOperationsAllowed = currentState.nodes().getMinNodeVersion().onOrAfter(FULL_CONCURRENCY_VERSION);
                final SnapshotDeletionsInProgress deletionsInProgress = currentState.custom(
                    SnapshotDeletionsInProgress.TYPE,
                    SnapshotDeletionsInProgress.EMPTY
                );
                if (deletionsInProgress.hasDeletionsInProgress() && concurrentOperationsAllowed == false) {
                    throw new ConcurrentSnapshotExecutionException(
                        repositoryName,
                        snapshotName,
                        "cannot snapshot while a snapshot deletion is in-progress in [" + deletionsInProgress + "]"
                    );
                }
                final RepositoryCleanupInProgress repositoryCleanupInProgress = currentState.custom(
                    RepositoryCleanupInProgress.TYPE,
                    RepositoryCleanupInProgress.EMPTY
                );
                if (repositoryCleanupInProgress.hasCleanupInProgress()) {
                    throw new ConcurrentSnapshotExecutionException(
                        repositoryName,
                        snapshotName,
                        "cannot snapshot while a repository cleanup is in-progress in [" + repositoryCleanupInProgress + "]"
                    );
                }
                // Fail if there are any concurrently running snapshots. The only exception to this being a snapshot in INIT state from a
                // previous cluster-manager that we can simply ignore and remove from the cluster state because we would clean it up from
                // the
                // cluster state anyway in #applyClusterState.
                if (concurrentOperationsAllowed == false && runningSnapshots.stream().anyMatch(entry -> entry.state() != State.INIT)) {
                    throw new ConcurrentSnapshotExecutionException(repositoryName, snapshotName, " a snapshot is already running");
                }
                ensureNoCleanupInProgress(currentState, repositoryName, snapshotName);
                ensureBelowConcurrencyLimit(repositoryName, snapshotName, snapshots, deletionsInProgress);
                // Store newSnapshot here to be processed in clusterStateProcessed
                List<String> indices = Arrays.asList(indexNameExpressionResolver.concreteIndexNames(currentState, request));

                final List<String> dataStreams = indexNameExpressionResolver.dataStreamNames(
                    currentState,
                    request.indicesOptions(),
                    request.indices()
                );

                logger.trace("[{}][{}] creating snapshot for indices [{}]", repositoryName, snapshotName, indices);

                final List<IndexId> indexIds = repositoryData.resolveNewIndices(
                    indices,
                    getInFlightIndexIds(runningSnapshots, repositoryName)
                );
                final Version version = minCompatibleVersion(currentState.nodes().getMinNodeVersion(), repositoryData, null);
                ImmutableOpenMap<ShardId, ShardSnapshotStatus> shards = shards(
                    snapshots,
                    deletionsInProgress,
                    currentState.metadata(),
                    currentState.routingTable(),
                    indexIds,
                    useShardGenerations(version),
                    repositoryData,
                    repositoryName
                );
                if (request.partial() == false) {
                    Set<String> missing = new HashSet<>();
                    for (ObjectObjectCursor<ShardId, SnapshotsInProgress.ShardSnapshotStatus> entry : shards) {
                        if (entry.value.state() == ShardState.MISSING) {
                            missing.add(entry.key.getIndex().getName());
                        }
                    }
                    if (missing.isEmpty() == false) {
                        throw new SnapshotException(
                            new Snapshot(repositoryName, snapshotId),
                            "Indices don't have primary shards " + missing
                        );
                    }
                }
                newEntry = SnapshotsInProgress.startedEntry(
                    new Snapshot(repositoryName, snapshotId),
                    request.includeGlobalState(),
                    request.partial(),
                    indexIds,
                    dataStreams,
                    threadPool.absoluteTimeInMillis(),
                    repositoryData.getGenId(),
                    shards,
                    userMeta,
                    version
                );
                final List<SnapshotsInProgress.Entry> newEntries = new ArrayList<>(runningSnapshots);
                newEntries.add(newEntry);
                return ClusterState.builder(currentState)
                    .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.of(new ArrayList<>(newEntries)))
                    .build();
            }

            @Override
            public void onFailure(String source, Exception e) {
                logger.warn(() -> new ParameterizedMessage("[{}][{}] failed to create snapshot", repositoryName, snapshotName), e);
                listener.onFailure(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, final ClusterState newState) {
                try {
                    logger.info("snapshot [{}] started", snapshot);
                    listener.onResponse(snapshot);
                } finally {
                    if (newEntry.state().completed()) {
                        endSnapshot(newEntry, newState.metadata(), repositoryData);
                    }
                }
            }

            @Override
            public TimeValue timeout() {
                return request.clusterManagerNodeTimeout();
            }
        }, "create_snapshot [" + snapshotName + ']', listener::onFailure);
    }

    private static void ensureSnapshotNameNotRunning(
        List<SnapshotsInProgress.Entry> runningSnapshots,
        String repositoryName,
        String snapshotName
    ) {
        if (runningSnapshots.stream().anyMatch(s -> {
            final Snapshot running = s.snapshot();
            return running.getRepository().equals(repositoryName) && running.getSnapshotId().getName().equals(snapshotName);
        })) {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "snapshot with the same name is already in-progress");
        }
    }

    private static Map<String, IndexId> getInFlightIndexIds(List<SnapshotsInProgress.Entry> runningSnapshots, String repositoryName) {
        return runningSnapshots.stream()
            .filter(entry -> entry.repository().equals(repositoryName))
            .flatMap(entry -> entry.indices().stream())
            .distinct()
            .collect(Collectors.toMap(IndexId::getName, Function.identity()));
    }

    // TODO: It is worth revisiting the design choice of creating a placeholder entry in snapshots-in-progress here once we have a cache
    // for repository metadata and loading it has predictable performance
    public void cloneSnapshot(CloneSnapshotRequest request, ActionListener<Void> listener) {
        final String repositoryName = request.repository();
        Repository repository = repositoriesService.repository(repositoryName);
        if (repository.isReadOnly()) {
            listener.onFailure(new RepositoryException(repositoryName, "cannot create snapshot in a readonly repository"));
            return;
        }
        final String snapshotName = indexNameExpressionResolver.resolveDateMathExpression(request.target());
        validate(repositoryName, snapshotName);
        // TODO: create snapshot UUID in CloneSnapshotRequest and make this operation idempotent to cleanly deal with transport layer
        // retries
        final SnapshotId snapshotId = new SnapshotId(snapshotName, UUIDs.randomBase64UUID());
        final Snapshot snapshot = new Snapshot(repositoryName, snapshotId);
        initializingClones.add(snapshot);
        repository.executeConsistentStateUpdate(repositoryData -> new ClusterStateUpdateTask() {

            private SnapshotsInProgress.Entry newEntry;

            @Override
            public ClusterState execute(ClusterState currentState) {
                ensureSnapshotNameAvailableInRepo(repositoryData, snapshotName, repository);
                ensureNoCleanupInProgress(currentState, repositoryName, snapshotName);
                final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                final List<SnapshotsInProgress.Entry> runningSnapshots = snapshots.entries();
                ensureSnapshotNameNotRunning(runningSnapshots, repositoryName, snapshotName);
                validate(repositoryName, snapshotName, currentState);

                final SnapshotId sourceSnapshotId = repositoryData.getSnapshotIds()
                    .stream()
                    .filter(src -> src.getName().equals(request.source()))
                    .findAny()
                    .orElseThrow(() -> new SnapshotMissingException(repositoryName, request.source()));
                final SnapshotDeletionsInProgress deletionsInProgress = currentState.custom(
                    SnapshotDeletionsInProgress.TYPE,
                    SnapshotDeletionsInProgress.EMPTY
                );
                if (deletionsInProgress.getEntries().stream().anyMatch(entry -> entry.getSnapshots().contains(sourceSnapshotId))) {
                    throw new ConcurrentSnapshotExecutionException(
                        repositoryName,
                        sourceSnapshotId.getName(),
                        "cannot clone from snapshot that is being deleted"
                    );
                }
                ensureBelowConcurrencyLimit(repositoryName, snapshotName, snapshots, deletionsInProgress);
                final List<String> indicesForSnapshot = new ArrayList<>();
                for (IndexId indexId : repositoryData.getIndices().values()) {
                    if (repositoryData.getSnapshots(indexId).contains(sourceSnapshotId)) {
                        indicesForSnapshot.add(indexId.getName());
                    }
                }
                final List<String> matchingIndices = SnapshotUtils.filterIndices(
                    indicesForSnapshot,
                    request.indices(),
                    request.indicesOptions()
                );
                if (matchingIndices.isEmpty()) {
                    throw new SnapshotException(
                        new Snapshot(repositoryName, sourceSnapshotId),
                        "No indices in the source snapshot ["
                            + sourceSnapshotId
                            + "] matched requested pattern ["
                            + Strings.arrayToCommaDelimitedString(request.indices())
                            + "]"
                    );
                }
                newEntry = SnapshotsInProgress.startClone(
                    snapshot,
                    sourceSnapshotId,
                    repositoryData.resolveIndices(matchingIndices),
                    threadPool.absoluteTimeInMillis(),
                    repositoryData.getGenId(),
                    minCompatibleVersion(currentState.nodes().getMinNodeVersion(), repositoryData, null)
                );
                final List<SnapshotsInProgress.Entry> newEntries = new ArrayList<>(runningSnapshots);
                newEntries.add(newEntry);
                return ClusterState.builder(currentState).putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.of(newEntries)).build();
            }

            @Override
            public void onFailure(String source, Exception e) {
                initializingClones.remove(snapshot);
                logger.warn(() -> new ParameterizedMessage("[{}][{}] failed to clone snapshot", repositoryName, snapshotName), e);
                listener.onFailure(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, final ClusterState newState) {
                logger.info("snapshot clone [{}] started", snapshot);
                addListener(snapshot, ActionListener.wrap(r -> listener.onResponse(null), listener::onFailure));
                startCloning(repository, newEntry);
            }

            @Override
            public TimeValue timeout() {
                return request.clusterManagerNodeTimeout();
            }
        }, "clone_snapshot [" + request.source() + "][" + snapshotName + ']', listener::onFailure);
    }

    private static void ensureNoCleanupInProgress(ClusterState currentState, String repositoryName, String snapshotName) {
        final RepositoryCleanupInProgress repositoryCleanupInProgress = currentState.custom(
            RepositoryCleanupInProgress.TYPE,
            RepositoryCleanupInProgress.EMPTY
        );
        if (repositoryCleanupInProgress.hasCleanupInProgress()) {
            throw new ConcurrentSnapshotExecutionException(
                repositoryName,
                snapshotName,
                "cannot snapshot while a repository cleanup is in-progress in [" + repositoryCleanupInProgress + "]"
            );
        }
    }

    private static void ensureSnapshotNameAvailableInRepo(RepositoryData repositoryData, String snapshotName, Repository repository) {
        // check if the snapshot name already exists in the repository
        if (repositoryData.getSnapshotIds().stream().anyMatch(s -> s.getName().equals(snapshotName))) {
            throw new InvalidSnapshotNameException(
                repository.getMetadata().name(),
                snapshotName,
                "snapshot with the same name already exists"
            );
        }
    }

    /**
     * Determine the number of shards in each index of a clone operation and update the cluster state accordingly.
     *
     * @param repository     repository to run operation on
     * @param cloneEntry     clone operation in the cluster state
     */
    private void startCloning(Repository repository, SnapshotsInProgress.Entry cloneEntry) {
        final List<IndexId> indices = cloneEntry.indices();
        final SnapshotId sourceSnapshot = cloneEntry.source();
        final Snapshot targetSnapshot = cloneEntry.snapshot();

        final Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
        // Exception handler for IO exceptions with loading index and repo metadata
        final Consumer<Exception> onFailure = e -> {
            initializingClones.remove(targetSnapshot);
            logger.info(() -> new ParameterizedMessage("Failed to start snapshot clone [{}]", cloneEntry), e);
            removeFailedSnapshotFromClusterState(targetSnapshot, e, null, null);
        };

        // 1. step, load SnapshotInfo to make sure that source snapshot was successful for the indices we want to clone
        // TODO: we could skip this step for snapshots with state SUCCESS
        final StepListener<SnapshotInfo> snapshotInfoListener = new StepListener<>();
        executor.execute(ActionRunnable.supply(snapshotInfoListener, () -> repository.getSnapshotInfo(sourceSnapshot)));

        final StepListener<Collection<Tuple<IndexId, Integer>>> allShardCountsListener = new StepListener<>();
        final GroupedActionListener<Tuple<IndexId, Integer>> shardCountListener = new GroupedActionListener<>(
            allShardCountsListener,
            indices.size()
        );
        snapshotInfoListener.whenComplete(snapshotInfo -> {
            for (IndexId indexId : indices) {
                if (RestoreService.failed(snapshotInfo, indexId.getName())) {
                    throw new SnapshotException(
                        targetSnapshot,
                        "Can't clone index [" + indexId + "] because its snapshot was not successful."
                    );
                }
            }
            // 2. step, load the number of shards we have in each index to be cloned from the index metadata.
            repository.getRepositoryData(ActionListener.wrap(repositoryData -> {
                for (IndexId index : indices) {
                    executor.execute(ActionRunnable.supply(shardCountListener, () -> {
                        final IndexMetadata metadata = repository.getSnapshotIndexMetaData(repositoryData, sourceSnapshot, index);
                        return Tuple.tuple(index, metadata.getNumberOfShards());
                    }));
                }
            }, onFailure));
        }, onFailure);

        // 3. step, we have all the shard counts, now update the cluster state to have clone jobs in the snap entry
        allShardCountsListener.whenComplete(counts -> repository.executeConsistentStateUpdate(repoData -> new ClusterStateUpdateTask() {

            private SnapshotsInProgress.Entry updatedEntry;

            @Override
            public ClusterState execute(ClusterState currentState) {
                final SnapshotsInProgress snapshotsInProgress = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                final List<SnapshotsInProgress.Entry> updatedEntries = new ArrayList<>(snapshotsInProgress.entries());
                boolean changed = false;
                final String localNodeId = currentState.nodes().getLocalNodeId();
                final String repoName = cloneEntry.repository();
                final ShardGenerations shardGenerations = repoData.shardGenerations();
                for (int i = 0; i < updatedEntries.size(); i++) {
                    if (cloneEntry.snapshot().equals(updatedEntries.get(i).snapshot())) {
                        final ImmutableOpenMap.Builder<RepositoryShardId, ShardSnapshotStatus> clonesBuilder = ImmutableOpenMap.builder();
                        final InFlightShardSnapshotStates inFlightShardStates = InFlightShardSnapshotStates.forRepo(
                            repoName,
                            snapshotsInProgress.entries()
                        );
                        for (Tuple<IndexId, Integer> count : counts) {
                            for (int shardId = 0; shardId < count.v2(); shardId++) {
                                final RepositoryShardId repoShardId = new RepositoryShardId(count.v1(), shardId);
                                final String indexName = repoShardId.indexName();
                                if (inFlightShardStates.isActive(indexName, shardId)) {
                                    clonesBuilder.put(repoShardId, ShardSnapshotStatus.UNASSIGNED_QUEUED);
                                } else {
                                    clonesBuilder.put(
                                        repoShardId,
                                        new ShardSnapshotStatus(
                                            localNodeId,
                                            inFlightShardStates.generationForShard(repoShardId.index(), shardId, shardGenerations)
                                        )
                                    );
                                }
                            }
                        }
                        updatedEntry = cloneEntry.withClones(clonesBuilder.build());
                        updatedEntries.set(i, updatedEntry);
                        changed = true;
                        break;
                    }
                }
                return updateWithSnapshots(currentState, changed ? SnapshotsInProgress.of(updatedEntries) : null, null);
            }

            @Override
            public void onFailure(String source, Exception e) {
                initializingClones.remove(targetSnapshot);
                logger.info(() -> new ParameterizedMessage("Failed to start snapshot clone [{}]", cloneEntry), e);
                failAllListenersOnMasterFailOver(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                initializingClones.remove(targetSnapshot);
                if (updatedEntry != null) {
                    final Snapshot target = updatedEntry.snapshot();
                    final SnapshotId sourceSnapshot = updatedEntry.source();
                    for (ObjectObjectCursor<RepositoryShardId, ShardSnapshotStatus> indexClone : updatedEntry.clones()) {
                        final ShardSnapshotStatus shardStatusBefore = indexClone.value;
                        if (shardStatusBefore.state() != ShardState.INIT) {
                            continue;
                        }
                        final RepositoryShardId repoShardId = indexClone.key;
                        runReadyClone(target, sourceSnapshot, shardStatusBefore, repoShardId, repository);
                    }
                } else {
                    // Extremely unlikely corner case of cluster-manager failing over between between starting the clone and
                    // starting shard clones.
                    logger.warn("Did not find expected entry [{}] in the cluster state", cloneEntry);
                }
            }
        }, "start snapshot clone", onFailure), onFailure);
    }

    private final Set<RepositoryShardId> currentlyCloning = Collections.synchronizedSet(new HashSet<>());

    private void runReadyClone(
        Snapshot target,
        SnapshotId sourceSnapshot,
        ShardSnapshotStatus shardStatusBefore,
        RepositoryShardId repoShardId,
        Repository repository
    ) {
        final SnapshotId targetSnapshot = target.getSnapshotId();
        final String localNodeId = clusterService.localNode().getId();
        if (currentlyCloning.add(repoShardId)) {
            repository.cloneShardSnapshot(
                sourceSnapshot,
                targetSnapshot,
                repoShardId,
                shardStatusBefore.generation(),
                ActionListener.wrap(
                    generation -> innerUpdateSnapshotState(
                        new ShardSnapshotUpdate(target, repoShardId, new ShardSnapshotStatus(localNodeId, ShardState.SUCCESS, generation)),
                        ActionListener.runBefore(
                            ActionListener.wrap(
                                v -> logger.trace(
                                    "Marked [{}] as successfully cloned from [{}] to [{}]",
                                    repoShardId,
                                    sourceSnapshot,
                                    targetSnapshot
                                ),
                                e -> {
                                    logger.warn("Cluster state update after successful shard clone [{}] failed", repoShardId);
                                    failAllListenersOnMasterFailOver(e);
                                }
                            ),
                            () -> currentlyCloning.remove(repoShardId)
                        )
                    ),
                    e -> innerUpdateSnapshotState(
                        new ShardSnapshotUpdate(
                            target,
                            repoShardId,
                            new ShardSnapshotStatus(localNodeId, ShardState.FAILED, "failed to clone shard snapshot", null)
                        ),
                        ActionListener.runBefore(
                            ActionListener.wrap(
                                v -> logger.trace(
                                    "Marked [{}] as failed clone from [{}] to [{}]",
                                    repoShardId,
                                    sourceSnapshot,
                                    targetSnapshot
                                ),
                                ex -> {
                                    logger.warn("Cluster state update after failed shard clone [{}] failed", repoShardId);
                                    failAllListenersOnMasterFailOver(ex);
                                }
                            ),
                            () -> currentlyCloning.remove(repoShardId)
                        )
                    )
                )
            );
        }
    }

    private void ensureBelowConcurrencyLimit(
        String repository,
        String name,
        SnapshotsInProgress snapshotsInProgress,
        SnapshotDeletionsInProgress deletionsInProgress
    ) {
        final int inProgressOperations = snapshotsInProgress.entries().size() + deletionsInProgress.getEntries().size();
        final int maxOps = maxConcurrentOperations;
        if (inProgressOperations >= maxOps) {
            throw new ConcurrentSnapshotExecutionException(
                repository,
                name,
                "Cannot start another operation, already running ["
                    + inProgressOperations
                    + "] operations and the current"
                    + " limit for concurrent snapshot operations is set to ["
                    + maxOps
                    + "]"
            );
        }
    }

    /**
     * Validates snapshot request
     *
     * @param repositoryName repository name
     * @param snapshotName snapshot name
     * @param state   current cluster state
     */
    private static void validate(String repositoryName, String snapshotName, ClusterState state) {
        RepositoriesMetadata repositoriesMetadata = state.getMetadata().custom(RepositoriesMetadata.TYPE);
        if (repositoriesMetadata == null || repositoriesMetadata.repository(repositoryName) == null) {
            throw new RepositoryMissingException(repositoryName);
        }
        validate(repositoryName, snapshotName);
    }

    private static void validate(final String repositoryName, final String snapshotName) {
        if (Strings.hasLength(snapshotName) == false) {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "cannot be empty");
        }
        if (snapshotName.contains(" ")) {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "must not contain whitespace");
        }
        if (snapshotName.contains(",")) {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "must not contain ','");
        }
        if (snapshotName.contains("#")) {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "must not contain '#'");
        }
        if (snapshotName.charAt(0) == '_') {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "must not start with '_'");
        }
        if (snapshotName.toLowerCase(Locale.ROOT).equals(snapshotName) == false) {
            throw new InvalidSnapshotNameException(repositoryName, snapshotName, "must be lowercase");
        }
        if (Strings.validFileName(snapshotName) == false) {
            throw new InvalidSnapshotNameException(
                repositoryName,
                snapshotName,
                "must not contain the following characters " + Strings.INVALID_FILENAME_CHARS
            );
        }
    }

    /**
     * Starts snapshot.
     * <p>
     * Creates snapshot in repository and updates snapshot metadata record with list of shards that needs to be processed.
     * Note: This method is only used in clusters that contain a node older than {@link #NO_REPO_INITIALIZE_VERSION} to ensure a backwards
     * compatible path for initializing the snapshot in the repository is executed.
     *
     * @param clusterState               cluster state
     * @param snapshot                   snapshot meta data
     * @param partial                    allow partial snapshots
     * @param userCreateSnapshotListener listener
     */
    private void beginSnapshot(
        final ClusterState clusterState,
        final SnapshotsInProgress.Entry snapshot,
        final boolean partial,
        final List<String> indices,
        final Repository repository,
        final ActionListener<Snapshot> userCreateSnapshotListener
    ) {
        threadPool.executor(ThreadPool.Names.SNAPSHOT).execute(new AbstractRunnable() {

            boolean hadAbortedInitializations;

            @Override
            protected void doRun() {
                assert initializingSnapshots.contains(snapshot.snapshot());
                if (repository.isReadOnly()) {
                    throw new RepositoryException(repository.getMetadata().name(), "cannot create snapshot in a readonly repository");
                }
                final String snapshotName = snapshot.snapshot().getSnapshotId().getName();
                final StepListener<RepositoryData> repositoryDataListener = new StepListener<>();
                repository.getRepositoryData(repositoryDataListener);
                repositoryDataListener.whenComplete(repositoryData -> {
                    // check if the snapshot name already exists in the repository
                    if (repositoryData.getSnapshotIds().stream().anyMatch(s -> s.getName().equals(snapshotName))) {
                        throw new InvalidSnapshotNameException(
                            repository.getMetadata().name(),
                            snapshotName,
                            "snapshot with the same name already exists"
                        );
                    }
                    if (clusterState.nodes().getMinNodeVersion().onOrAfter(NO_REPO_INITIALIZE_VERSION) == false) {
                        // In mixed version clusters we initialize the snapshot in the repository so that in case of a cluster-manager
                        // failover to an
                        // older version cluster-manager node snapshot finalization (that assumes initializeSnapshot was called) produces a
                        // valid
                        // snapshot.
                        repository.initializeSnapshot(
                            snapshot.snapshot().getSnapshotId(),
                            snapshot.indices(),
                            metadataForSnapshot(snapshot, clusterState.metadata())
                        );
                    }

                    logger.info("snapshot [{}] started", snapshot.snapshot());
                    final Version version = minCompatibleVersion(clusterState.nodes().getMinNodeVersion(), repositoryData, null);
                    if (indices.isEmpty()) {
                        // No indices in this snapshot - we are done
                        userCreateSnapshotListener.onResponse(snapshot.snapshot());
                        endSnapshot(
                            SnapshotsInProgress.startedEntry(
                                snapshot.snapshot(),
                                snapshot.includeGlobalState(),
                                snapshot.partial(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                threadPool.absoluteTimeInMillis(),
                                repositoryData.getGenId(),
                                ImmutableOpenMap.of(),
                                snapshot.userMetadata(),
                                version
                            ),
                            clusterState.metadata(),
                            repositoryData
                        );
                        return;
                    }
                    clusterService.submitStateUpdateTask("update_snapshot [" + snapshot.snapshot() + "]", new ClusterStateUpdateTask() {

                        @Override
                        public ClusterState execute(ClusterState currentState) {
                            SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE);
                            List<SnapshotsInProgress.Entry> entries = new ArrayList<>();
                            for (SnapshotsInProgress.Entry entry : snapshots.entries()) {
                                if (entry.snapshot().equals(snapshot.snapshot()) == false) {
                                    entries.add(entry);
                                    continue;
                                }

                                if (entry.state() == State.ABORTED) {
                                    entries.add(entry);
                                    assert entry.shards().isEmpty();
                                    hadAbortedInitializations = true;
                                } else {
                                    final List<IndexId> indexIds = repositoryData.resolveNewIndices(indices, Collections.emptyMap());
                                    // Replace the snapshot that was just initialized
                                    ImmutableOpenMap<ShardId, ShardSnapshotStatus> shards = shards(
                                        snapshots,
                                        currentState.custom(SnapshotDeletionsInProgress.TYPE, SnapshotDeletionsInProgress.EMPTY),
                                        currentState.metadata(),
                                        currentState.routingTable(),
                                        indexIds,
                                        useShardGenerations(version),
                                        repositoryData,
                                        entry.repository()
                                    );
                                    if (!partial) {
                                        Tuple<Set<String>, Set<String>> indicesWithMissingShards = indicesWithMissingShards(
                                            shards,
                                            currentState.metadata()
                                        );
                                        Set<String> missing = indicesWithMissingShards.v1();
                                        Set<String> closed = indicesWithMissingShards.v2();
                                        if (missing.isEmpty() == false || closed.isEmpty() == false) {
                                            final StringBuilder failureMessage = new StringBuilder();
                                            if (missing.isEmpty() == false) {
                                                failureMessage.append("Indices don't have primary shards ");
                                                failureMessage.append(missing);
                                            }
                                            if (closed.isEmpty() == false) {
                                                if (failureMessage.length() > 0) {
                                                    failureMessage.append("; ");
                                                }
                                                failureMessage.append("Indices are closed ");
                                                failureMessage.append(closed);
                                            }
                                            entries.add(
                                                new SnapshotsInProgress.Entry(
                                                    entry,
                                                    State.FAILED,
                                                    indexIds,
                                                    repositoryData.getGenId(),
                                                    shards,
                                                    version,
                                                    failureMessage.toString()
                                                )
                                            );
                                            continue;
                                        }
                                    }
                                    entries.add(
                                        new SnapshotsInProgress.Entry(
                                            entry,
                                            State.STARTED,
                                            indexIds,
                                            repositoryData.getGenId(),
                                            shards,
                                            version,
                                            null
                                        )
                                    );
                                }
                            }
                            return ClusterState.builder(currentState)
                                .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.of(unmodifiableList(entries)))
                                .build();
                        }

                        @Override
                        public void onFailure(String source, Exception e) {
                            logger.warn(
                                () -> new ParameterizedMessage("[{}] failed to create snapshot", snapshot.snapshot().getSnapshotId()),
                                e
                            );
                            removeFailedSnapshotFromClusterState(
                                snapshot.snapshot(),
                                e,
                                null,
                                new CleanupAfterErrorListener(userCreateSnapshotListener, e)
                            );
                        }

                        @Override
                        public void onNoLongerClusterManager(String source) {
                            // We are not longer a cluster-manager - we shouldn't try to do any cleanup
                            // The new cluster-manager will take care of it
                            logger.warn(
                                "[{}] failed to create snapshot - no longer a cluster-manager",
                                snapshot.snapshot().getSnapshotId()
                            );
                            userCreateSnapshotListener.onFailure(
                                new SnapshotException(snapshot.snapshot(), "cluster-manager changed during snapshot initialization")
                            );
                        }

                        @Override
                        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                            // The userCreateSnapshotListener.onResponse() notifies caller that the snapshot was accepted
                            // for processing. If client wants to wait for the snapshot completion, it can register snapshot
                            // completion listener in this method. For the snapshot completion to work properly, the snapshot
                            // should still exist when listener is registered.
                            userCreateSnapshotListener.onResponse(snapshot.snapshot());

                            if (hadAbortedInitializations) {
                                final SnapshotsInProgress snapshotsInProgress = newState.custom(SnapshotsInProgress.TYPE);
                                assert snapshotsInProgress != null;
                                final SnapshotsInProgress.Entry entry = snapshotsInProgress.snapshot(snapshot.snapshot());
                                assert entry != null;
                                endSnapshot(entry, newState.metadata(), repositoryData);
                            } else {
                                endCompletedSnapshots(newState);
                            }
                        }
                    });
                }, this::onFailure);
            }

            @Override
            public void onFailure(Exception e) {
                logger.warn(() -> new ParameterizedMessage("failed to create snapshot [{}]", snapshot.snapshot().getSnapshotId()), e);
                removeFailedSnapshotFromClusterState(
                    snapshot.snapshot(),
                    e,
                    null,
                    new CleanupAfterErrorListener(userCreateSnapshotListener, e)
                );
            }
        });
    }

    private static class CleanupAfterErrorListener {

        private final ActionListener<Snapshot> userCreateSnapshotListener;
        private final Exception e;

        CleanupAfterErrorListener(ActionListener<Snapshot> userCreateSnapshotListener, Exception e) {
            this.userCreateSnapshotListener = userCreateSnapshotListener;
            this.e = e;
        }

        public void onFailure(@Nullable Exception e) {
            userCreateSnapshotListener.onFailure(ExceptionsHelper.useOrSuppress(e, this.e));
        }

        public void onNoLongerClusterManager() {
            userCreateSnapshotListener.onFailure(e);
        }
    }

    private static ShardGenerations buildGenerations(SnapshotsInProgress.Entry snapshot, Metadata metadata) {
        ShardGenerations.Builder builder = ShardGenerations.builder();
        final Map<String, IndexId> indexLookup = new HashMap<>();
        snapshot.indices().forEach(idx -> indexLookup.put(idx.getName(), idx));
        if (snapshot.isClone()) {
            snapshot.clones().forEach(c -> {
                final IndexId indexId = indexLookup.get(c.key.indexName());
                builder.put(indexId, c.key.shardId(), c.value.generation());
            });
        } else {
            snapshot.shards().forEach(c -> {
                if (metadata.index(c.key.getIndex()) == null) {
                    assert snapshot.partial() : "Index ["
                        + c.key.getIndex()
                        + "] was deleted during a snapshot but snapshot was not partial.";
                    return;
                }
                final IndexId indexId = indexLookup.get(c.key.getIndexName());
                if (indexId != null) {
                    builder.put(indexId, c.key.id(), c.value.generation());
                }
            });
        }
        return builder.build();
    }

    private static Metadata metadataForSnapshot(SnapshotsInProgress.Entry snapshot, Metadata metadata) {
        final Metadata.Builder builder;
        if (snapshot.includeGlobalState() == false) {
            // Remove global state from the cluster state
            builder = Metadata.builder();
            for (IndexId index : snapshot.indices()) {
                final IndexMetadata indexMetadata = metadata.index(index.getName());
                if (indexMetadata == null) {
                    assert snapshot.partial() : "Index [" + index + "] was deleted during a snapshot but snapshot was not partial.";
                } else {
                    builder.put(indexMetadata, false);
                }
            }
        } else {
            builder = Metadata.builder(metadata);
        }
        // Only keep those data streams in the metadata that were actually requested by the initial snapshot create operation
        Map<String, DataStream> dataStreams = new HashMap<>();
        for (String dataStreamName : snapshot.dataStreams()) {
            DataStream dataStream = metadata.dataStreams().get(dataStreamName);
            if (dataStream == null) {
                assert snapshot.partial() : "Data stream ["
                    + dataStreamName
                    + "] was deleted during a snapshot but snapshot was not partial.";
            } else {
                dataStreams.put(dataStreamName, dataStream);
            }
        }
        return builder.dataStreams(dataStreams).build();
    }

    /**
     * Returns status of the currently running snapshots
     * <p>
     * This method is executed on cluster-manager node
     * </p>
     *
     * @param snapshotsInProgress snapshots in progress in the cluster state
     * @param repository          repository id
     * @param snapshots           list of snapshots that will be used as a filter, empty list means no snapshots are filtered
     * @return list of metadata for currently running snapshots
     */
    public static List<SnapshotsInProgress.Entry> currentSnapshots(
        @Nullable SnapshotsInProgress snapshotsInProgress,
        String repository,
        List<String> snapshots
    ) {
        if (snapshotsInProgress == null || snapshotsInProgress.entries().isEmpty()) {
            return Collections.emptyList();
        }
        if ("_all".equals(repository)) {
            return snapshotsInProgress.entries();
        }
        if (snapshotsInProgress.entries().size() == 1) {
            // Most likely scenario - one snapshot is currently running
            // Check this snapshot against the query
            SnapshotsInProgress.Entry entry = snapshotsInProgress.entries().get(0);
            if (entry.snapshot().getRepository().equals(repository) == false) {
                return Collections.emptyList();
            }
            if (snapshots.isEmpty() == false) {
                for (String snapshot : snapshots) {
                    if (entry.snapshot().getSnapshotId().getName().equals(snapshot)) {
                        return snapshotsInProgress.entries();
                    }
                }
                return Collections.emptyList();
            } else {
                return snapshotsInProgress.entries();
            }
        }
        List<SnapshotsInProgress.Entry> builder = new ArrayList<>();
        for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
            if (entry.snapshot().getRepository().equals(repository) == false) {
                continue;
            }
            if (snapshots.isEmpty() == false) {
                for (String snapshot : snapshots) {
                    if (entry.snapshot().getSnapshotId().getName().equals(snapshot)) {
                        builder.add(entry);
                        break;
                    }
                }
            } else {
                builder.add(entry);
            }
        }
        return unmodifiableList(builder);
    }

    @Override
    public void applyClusterState(ClusterChangedEvent event) {
        try {
            if (event.localNodeClusterManager()) {
                // We don't remove old cluster-manager when cluster-manager flips anymore. So, we need to check for change in
                // cluster-manager
                SnapshotsInProgress snapshotsInProgress = event.state().custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                final boolean newClusterManager = event.previousState().nodes().isLocalNodeElectedClusterManager() == false;
                processExternalChanges(
                    newClusterManager || removedNodesCleanupNeeded(snapshotsInProgress, event.nodesDelta().removedNodes()),
                    event.routingTableChanged() && waitingShardsStartedOrUnassigned(snapshotsInProgress, event)
                );
            } else if (snapshotCompletionListeners.isEmpty() == false) {
                // We have snapshot listeners but are not the cluster-manager any more. Fail all waiting listeners except for those that
                // already
                // have their snapshots finalizing (those that are already finalizing will fail on their own from to update the cluster
                // state).
                for (Snapshot snapshot : new HashSet<>(snapshotCompletionListeners.keySet())) {
                    if (endingSnapshots.add(snapshot)) {
                        failSnapshotCompletionListeners(snapshot, new SnapshotException(snapshot, "no longer cluster-manager"));
                    }
                }
            }
        } catch (Exception e) {
            assert false : new AssertionError(e);
            logger.warn("Failed to update snapshot state ", e);
        }
        assert assertConsistentWithClusterState(event.state());
        assert assertNoDanglingSnapshots(event.state());
    }

    /**
     * Cleanup all snapshots found in the given cluster state that have no more work left:
     * 1. Completed snapshots
     * 2. Snapshots in state INIT that a previous cluster-manager of an older version failed to start
     * 3. Snapshots in any other state that have all their shard tasks completed
     */
    private void endCompletedSnapshots(ClusterState state) {
        SnapshotsInProgress snapshotsInProgress = state.custom(SnapshotsInProgress.TYPE);
        assert snapshotsInProgress != null;
        snapshotsInProgress.entries()
            .stream()
            .filter(entry -> entry.state().completed() || entry.state() == State.INIT || completed(entry.shards().values()))
            .forEach(entry -> endSnapshot(entry, state.metadata(), null));
    }

    private boolean assertConsistentWithClusterState(ClusterState state) {
        final SnapshotsInProgress snapshotsInProgress = state.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
        if (snapshotsInProgress.entries().isEmpty() == false) {
            synchronized (endingSnapshots) {
                final Set<Snapshot> runningSnapshots = Stream.concat(
                    snapshotsInProgress.entries().stream().map(SnapshotsInProgress.Entry::snapshot),
                    endingSnapshots.stream()
                ).collect(Collectors.toSet());
                final Set<Snapshot> snapshotListenerKeys = snapshotCompletionListeners.keySet();
                assert runningSnapshots.containsAll(snapshotListenerKeys) : "Saw completion listeners for unknown snapshots in "
                    + snapshotListenerKeys
                    + " but running snapshots are "
                    + runningSnapshots;
            }
        }
        final SnapshotDeletionsInProgress snapshotDeletionsInProgress = state.custom(
            SnapshotDeletionsInProgress.TYPE,
            SnapshotDeletionsInProgress.EMPTY
        );
        if (snapshotDeletionsInProgress.hasDeletionsInProgress()) {
            synchronized (repositoryOperations.runningDeletions) {
                final Set<String> runningDeletes = Stream.concat(
                    snapshotDeletionsInProgress.getEntries().stream().map(SnapshotDeletionsInProgress.Entry::uuid),
                    repositoryOperations.runningDeletions.stream()
                ).collect(Collectors.toSet());
                final Set<String> deleteListenerKeys = snapshotDeletionListeners.keySet();
                assert runningDeletes.containsAll(deleteListenerKeys) : "Saw deletions listeners for unknown uuids in "
                    + deleteListenerKeys
                    + " but running deletes are "
                    + runningDeletes;
            }
        }
        return true;
    }

    // Assert that there are no snapshots that have a shard that is waiting to be assigned even though the cluster state would allow for it
    // to be assigned
    private static boolean assertNoDanglingSnapshots(ClusterState state) {
        final SnapshotsInProgress snapshotsInProgress = state.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
        final SnapshotDeletionsInProgress snapshotDeletionsInProgress = state.custom(
            SnapshotDeletionsInProgress.TYPE,
            SnapshotDeletionsInProgress.EMPTY
        );
        final Set<String> reposWithRunningDelete = snapshotDeletionsInProgress.getEntries()
            .stream()
            .filter(entry -> entry.state() == SnapshotDeletionsInProgress.State.STARTED)
            .map(SnapshotDeletionsInProgress.Entry::repository)
            .collect(Collectors.toSet());
        final Set<String> reposSeen = new HashSet<>();
        for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
            if (reposSeen.add(entry.repository())) {
                for (ObjectCursor<ShardSnapshotStatus> value : entry.shards().values()) {
                    if (value.value.equals(ShardSnapshotStatus.UNASSIGNED_QUEUED)) {
                        assert reposWithRunningDelete.contains(entry.repository()) : "Found shard snapshot waiting to be assigned in ["
                            + entry
                            + "] but it is not blocked by any running delete";
                    }
                }
            }
        }
        return true;
    }

    /**
     * Updates the state of in-progress snapshots in reaction to a change in the configuration of the cluster nodes (cluster-manager fail-over or
     * disconnect of a data node that was executing a snapshot) or a routing change that started shards whose snapshot state is
     * {@link SnapshotsInProgress.ShardState#WAITING}.
     *
     * @param changedNodes true iff either a cluster-manager fail-over occurred or a data node that was doing snapshot work got removed from the
     *                     cluster
     * @param startShards  true iff any waiting shards were started due to a routing change
     */
    private void processExternalChanges(boolean changedNodes, boolean startShards) {
        if (changedNodes == false && startShards == false) {
            // nothing to do, no relevant external change happened
            return;
        }
        clusterService.submitStateUpdateTask(
            "update snapshot after shards started [" + startShards + "] or node configuration changed [" + changedNodes + "]",
            new ClusterStateUpdateTask() {

                private final Collection<SnapshotsInProgress.Entry> finishedSnapshots = new ArrayList<>();

                private final Collection<SnapshotDeletionsInProgress.Entry> deletionsToExecute = new ArrayList<>();

                @Override
                public ClusterState execute(ClusterState currentState) {
                    RoutingTable routingTable = currentState.routingTable();
                    final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                    DiscoveryNodes nodes = currentState.nodes();
                    boolean changed = false;
                    final EnumSet<State> statesToUpdate;
                    // If we are reacting to a change in the cluster node configuration we have to update the shard states of both started
                    // and
                    // aborted snapshots to potentially fail shards running on the removed nodes
                    if (changedNodes) {
                        statesToUpdate = EnumSet.of(State.STARTED, State.ABORTED);
                    } else {
                        // We are reacting to shards that started only so which only affects the individual shard states of started
                        // snapshots
                        statesToUpdate = EnumSet.of(State.STARTED);
                    }
                    ArrayList<SnapshotsInProgress.Entry> updatedSnapshotEntries = new ArrayList<>();

                    // We keep a cache of shards that failed in this map. If we fail a shardId for a given repository because of
                    // a node leaving or shard becoming unassigned for one snapshot, we will also fail it for all subsequent enqueued
                    // snapshots
                    // for the same repository
                    final Map<String, Map<ShardId, ShardSnapshotStatus>> knownFailures = new HashMap<>();

                    for (final SnapshotsInProgress.Entry snapshot : snapshots.entries()) {
                        if (statesToUpdate.contains(snapshot.state())) {
                            // Currently initializing clone
                            if (snapshot.isClone() && snapshot.clones().isEmpty()) {
                                if (initializingClones.contains(snapshot.snapshot())) {
                                    updatedSnapshotEntries.add(snapshot);
                                } else {
                                    logger.debug("removing not yet start clone operation [{}]", snapshot);
                                    changed = true;
                                }
                            } else {
                                ImmutableOpenMap<ShardId, ShardSnapshotStatus> shards = processWaitingShardsAndRemovedNodes(
                                    snapshot.shards(),
                                    routingTable,
                                    nodes,
                                    knownFailures.computeIfAbsent(snapshot.repository(), k -> new HashMap<>())
                                );
                                if (shards != null) {
                                    final SnapshotsInProgress.Entry updatedSnapshot = snapshot.withShardStates(shards);
                                    changed = true;
                                    if (updatedSnapshot.state().completed()) {
                                        finishedSnapshots.add(updatedSnapshot);
                                    }
                                    updatedSnapshotEntries.add(updatedSnapshot);
                                } else {
                                    updatedSnapshotEntries.add(snapshot);
                                }
                            }
                        } else if (snapshot.repositoryStateId() == RepositoryData.UNKNOWN_REPO_GEN) {
                            // BwC path, older versions could create entries with unknown repo GEN in INIT or ABORTED state that did not yet
                            // write anything to the repository physically. This means we can simply remove these from the cluster state
                            // without having to do any additional cleanup.
                            changed = true;
                            logger.debug("[{}] was found in dangling INIT or ABORTED state", snapshot);
                        } else {
                            if (snapshot.state().completed() || completed(snapshot.shards().values())) {
                                finishedSnapshots.add(snapshot);
                            }
                            updatedSnapshotEntries.add(snapshot);
                        }
                    }
                    final ClusterState res = readyDeletions(
                        changed
                            ? ClusterState.builder(currentState)
                                .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.of(unmodifiableList(updatedSnapshotEntries)))
                                .build()
                            : currentState
                    ).v1();
                    for (SnapshotDeletionsInProgress.Entry delete : res.custom(
                        SnapshotDeletionsInProgress.TYPE,
                        SnapshotDeletionsInProgress.EMPTY
                    ).getEntries()) {
                        if (delete.state() == SnapshotDeletionsInProgress.State.STARTED) {
                            deletionsToExecute.add(delete);
                        }
                    }
                    return res;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    logger.warn(
                        () -> new ParameterizedMessage(
                            "failed to update snapshot state after shards started or nodes removed from [{}] ",
                            source
                        ),
                        e
                    );
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    final SnapshotDeletionsInProgress snapshotDeletionsInProgress = newState.custom(
                        SnapshotDeletionsInProgress.TYPE,
                        SnapshotDeletionsInProgress.EMPTY
                    );
                    if (finishedSnapshots.isEmpty() == false) {
                        // If we found snapshots that should be finalized as a result of the CS update we try to initiate finalization for
                        // them
                        // unless there is an executing snapshot delete already. If there is an executing snapshot delete we don't have to
                        // enqueue the snapshot finalizations here because the ongoing delete will take care of that when removing the
                        // delete
                        // from the cluster state
                        final Set<String> reposWithRunningDeletes = snapshotDeletionsInProgress.getEntries()
                            .stream()
                            .filter(entry -> entry.state() == SnapshotDeletionsInProgress.State.STARTED)
                            .map(SnapshotDeletionsInProgress.Entry::repository)
                            .collect(Collectors.toSet());
                        for (SnapshotsInProgress.Entry entry : finishedSnapshots) {
                            if (reposWithRunningDeletes.contains(entry.repository()) == false) {
                                endSnapshot(entry, newState.metadata(), null);
                            }
                        }
                    }
                    startExecutableClones(newState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY), null);
                    // run newly ready deletes
                    for (SnapshotDeletionsInProgress.Entry entry : deletionsToExecute) {
                        if (tryEnterRepoLoop(entry.repository())) {
                            deleteSnapshotsFromRepository(entry, newState.nodes().getMinNodeVersion());
                        }
                    }
                }
            }
        );
    }

    private static ImmutableOpenMap<ShardId, ShardSnapshotStatus> processWaitingShardsAndRemovedNodes(
        ImmutableOpenMap<ShardId, ShardSnapshotStatus> snapshotShards,
        RoutingTable routingTable,
        DiscoveryNodes nodes,
        Map<ShardId, ShardSnapshotStatus> knownFailures
    ) {
        boolean snapshotChanged = false;
        ImmutableOpenMap.Builder<ShardId, ShardSnapshotStatus> shards = ImmutableOpenMap.builder();
        for (ObjectObjectCursor<ShardId, ShardSnapshotStatus> shardEntry : snapshotShards) {
            ShardSnapshotStatus shardStatus = shardEntry.value;
            ShardId shardId = shardEntry.key;
            if (shardStatus.equals(ShardSnapshotStatus.UNASSIGNED_QUEUED)) {
                // this shard snapshot is waiting for a previous snapshot to finish execution for this shard
                final ShardSnapshotStatus knownFailure = knownFailures.get(shardId);
                if (knownFailure == null) {
                    // if no failure is known for the shard we keep waiting
                    shards.put(shardId, shardStatus);
                } else {
                    // If a failure is known for an execution we waited on for this shard then we fail with the same exception here
                    // as well
                    snapshotChanged = true;
                    shards.put(shardId, knownFailure);
                }
            } else if (shardStatus.state() == ShardState.WAITING) {
                IndexRoutingTable indexShardRoutingTable = routingTable.index(shardId.getIndex());
                if (indexShardRoutingTable != null) {
                    IndexShardRoutingTable shardRouting = indexShardRoutingTable.shard(shardId.id());
                    if (shardRouting != null && shardRouting.primaryShard() != null) {
                        if (shardRouting.primaryShard().started()) {
                            // Shard that we were waiting for has started on a node, let's process it
                            snapshotChanged = true;
                            logger.trace("starting shard that we were waiting for [{}] on node [{}]", shardId, shardStatus.nodeId());
                            shards.put(
                                shardId,
                                new ShardSnapshotStatus(shardRouting.primaryShard().currentNodeId(), shardStatus.generation())
                            );
                            continue;
                        } else if (shardRouting.primaryShard().initializing() || shardRouting.primaryShard().relocating()) {
                            // Shard that we were waiting for hasn't started yet or still relocating - will continue to wait
                            shards.put(shardId, shardStatus);
                            continue;
                        }
                    }
                }
                // Shard that we were waiting for went into unassigned state or disappeared - giving up
                snapshotChanged = true;
                logger.warn("failing snapshot of shard [{}] on unassigned shard [{}]", shardId, shardStatus.nodeId());
                final ShardSnapshotStatus failedState = new ShardSnapshotStatus(
                    shardStatus.nodeId(),
                    ShardState.FAILED,
                    "shard is unassigned",
                    shardStatus.generation()
                );
                shards.put(shardId, failedState);
                knownFailures.put(shardId, failedState);
            } else if (shardStatus.state().completed() == false && shardStatus.nodeId() != null) {
                if (nodes.nodeExists(shardStatus.nodeId())) {
                    shards.put(shardId, shardStatus);
                } else {
                    // TODO: Restart snapshot on another node?
                    snapshotChanged = true;
                    logger.warn("failing snapshot of shard [{}] on closed node [{}]", shardId, shardStatus.nodeId());
                    final ShardSnapshotStatus failedState = new ShardSnapshotStatus(
                        shardStatus.nodeId(),
                        ShardState.FAILED,
                        "node shutdown",
                        shardStatus.generation()
                    );
                    shards.put(shardId, failedState);
                    knownFailures.put(shardId, failedState);
                }
            } else {
                shards.put(shardId, shardStatus);
            }
        }
        if (snapshotChanged) {
            return shards.build();
        } else {
            return null;
        }
    }

    private static boolean waitingShardsStartedOrUnassigned(SnapshotsInProgress snapshotsInProgress, ClusterChangedEvent event) {
        for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
            if (entry.state() == State.STARTED) {
                for (ObjectObjectCursor<ShardId, ShardSnapshotStatus> shardStatus : entry.shards()) {
                    if (shardStatus.value.state() != ShardState.WAITING) {
                        continue;
                    }
                    final ShardId shardId = shardStatus.key;
                    if (event.indexRoutingTableChanged(shardId.getIndexName())) {
                        IndexRoutingTable indexShardRoutingTable = event.state().getRoutingTable().index(shardId.getIndex());
                        if (indexShardRoutingTable == null) {
                            // index got removed concurrently and we have to fail WAITING state shards
                            return true;
                        }
                        ShardRouting shardRouting = indexShardRoutingTable.shard(shardId.id()).primaryShard();
                        if (shardRouting != null && (shardRouting.started() || shardRouting.unassigned())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean removedNodesCleanupNeeded(SnapshotsInProgress snapshotsInProgress, List<DiscoveryNode> removedNodes) {
        if (removedNodes.isEmpty()) {
            // Nothing to do, no nodes removed
            return false;
        }
        final Set<String> removedNodeIds = removedNodes.stream().map(DiscoveryNode::getId).collect(Collectors.toSet());
        return snapshotsInProgress.entries().stream().anyMatch(snapshot -> {
            if (snapshot.state().completed()) {
                // nothing to do for already completed snapshots
                return false;
            }
            for (ObjectCursor<ShardSnapshotStatus> shardStatus : snapshot.shards().values()) {
                final ShardSnapshotStatus shardSnapshotStatus = shardStatus.value;
                if (shardSnapshotStatus.state().completed() == false && removedNodeIds.contains(shardSnapshotStatus.nodeId())) {
                    // Snapshot had an incomplete shard running on a removed node so we need to adjust that shard's snapshot status
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Returns list of indices with missing shards, and list of indices that are closed
     *
     * @param shards list of shard statuses
     * @return list of failed and closed indices
     */
    private static Tuple<Set<String>, Set<String>> indicesWithMissingShards(
        ImmutableOpenMap<ShardId, SnapshotsInProgress.ShardSnapshotStatus> shards,
        Metadata metadata
    ) {
        Set<String> missing = new HashSet<>();
        Set<String> closed = new HashSet<>();
        for (ObjectObjectCursor<ShardId, SnapshotsInProgress.ShardSnapshotStatus> entry : shards) {
            if (entry.value.state() == ShardState.MISSING) {
                if (metadata.hasIndex(entry.key.getIndex().getName())
                    && metadata.getIndexSafe(entry.key.getIndex()).getState() == IndexMetadata.State.CLOSE) {
                    closed.add(entry.key.getIndex().getName());
                } else {
                    missing.add(entry.key.getIndex().getName());
                }
            }
        }
        return new Tuple<>(missing, closed);
    }

    /**
     * Finalizes the shard in repository and then removes it from cluster state
     * <p>
     * This is non-blocking method that runs on a thread from SNAPSHOT thread pool
     * Finalizes the snapshot in the repository.
     *
     * @param entry snapshot
     */
    private void endSnapshot(SnapshotsInProgress.Entry entry, Metadata metadata, @Nullable RepositoryData repositoryData) {
        final Snapshot snapshot = entry.snapshot();
        final boolean newFinalization = endingSnapshots.add(snapshot);
        if (entry.repositoryStateId() == RepositoryData.UNKNOWN_REPO_GEN) {
            logger.debug("[{}] was aborted before starting", snapshot);
            removeFailedSnapshotFromClusterState(
                entry.snapshot(),
                new SnapshotException(snapshot, "Aborted on initialization"),
                repositoryData,
                null
            );
            return;
        }
        if (entry.isClone() && entry.state() == State.FAILED) {
            logger.debug("Removing failed snapshot clone [{}] from cluster state", entry);
            removeFailedSnapshotFromClusterState(entry.snapshot(), new SnapshotException(entry.snapshot(), entry.failure()), null, null);
            return;
        }
        final String repoName = entry.repository();
        if (tryEnterRepoLoop(repoName)) {
            if (repositoryData == null) {
                repositoriesService.repository(repoName).getRepositoryData(new ActionListener<RepositoryData>() {
                    @Override
                    public void onResponse(RepositoryData repositoryData) {
                        finalizeSnapshotEntry(entry, metadata, repositoryData);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        clusterService.submitStateUpdateTask(
                            "fail repo tasks for [" + repoName + "]",
                            new FailPendingRepoTasksTask(repoName, e)
                        );
                    }
                });
            } else {
                finalizeSnapshotEntry(entry, metadata, repositoryData);
            }
        } else {
            if (newFinalization) {
                repositoryOperations.addFinalization(entry, metadata);
            }
        }
    }

    /**
     * Try starting to run a snapshot finalization or snapshot delete for the given repository. If this method returns
     * {@code true} then snapshot finalizations and deletions for the repo may be executed. Once no more operations are
     * ready for the repository {@link #leaveRepoLoop(String)} should be invoked so that a subsequent state change that
     * causes another operation to become ready can execute.
     *
     * @return true if a finalization or snapshot delete may be started at this point
     */
    private boolean tryEnterRepoLoop(String repository) {
        return currentlyFinalizing.add(repository);
    }

    /**
     * Stop polling for ready snapshot finalizations or deletes in state {@link SnapshotDeletionsInProgress.State#STARTED} to execute
     * for the given repository.
     */
    private void leaveRepoLoop(String repository) {
        final boolean removed = currentlyFinalizing.remove(repository);
        assert removed;
    }

    private void finalizeSnapshotEntry(SnapshotsInProgress.Entry entry, Metadata metadata, RepositoryData repositoryData) {
        assert currentlyFinalizing.contains(entry.repository());
        try {
            final String failure = entry.failure();
            final Snapshot snapshot = entry.snapshot();
            logger.trace("[{}] finalizing snapshot in repository, state: [{}], failure[{}]", snapshot, entry.state(), failure);
            ArrayList<SnapshotShardFailure> shardFailures = new ArrayList<>();
            for (ObjectObjectCursor<ShardId, ShardSnapshotStatus> shardStatus : entry.shards()) {
                ShardId shardId = shardStatus.key;
                ShardSnapshotStatus status = shardStatus.value;
                final ShardState state = status.state();
                if (state.failed()) {
                    shardFailures.add(new SnapshotShardFailure(status.nodeId(), shardId, status.reason()));
                } else if (state.completed() == false) {
                    shardFailures.add(new SnapshotShardFailure(status.nodeId(), shardId, "skipped"));
                } else {
                    assert state == ShardState.SUCCESS;
                }
            }
            final ShardGenerations shardGenerations = buildGenerations(entry, metadata);
            final String repository = snapshot.getRepository();
            final SnapshotInfo snapshotInfo = new SnapshotInfo(
                snapshot.getSnapshotId(),
                shardGenerations.indices().stream().map(IndexId::getName).collect(Collectors.toList()),
                entry.dataStreams(),
                entry.startTime(),
                failure,
                threadPool.absoluteTimeInMillis(),
                entry.partial() ? shardGenerations.totalShards() : entry.shards().size(),
                shardFailures,
                entry.includeGlobalState(),
                entry.userMetadata()
            );
            final StepListener<Metadata> metadataListener = new StepListener<>();
            final Repository repo = repositoriesService.repository(snapshot.getRepository());
            if (entry.isClone()) {
                threadPool.executor(ThreadPool.Names.SNAPSHOT).execute(ActionRunnable.supply(metadataListener, () -> {
                    final Metadata.Builder metaBuilder = Metadata.builder(repo.getSnapshotGlobalMetadata(entry.source()));
                    for (IndexId index : entry.indices()) {
                        metaBuilder.put(repo.getSnapshotIndexMetaData(repositoryData, entry.source(), index), false);
                    }
                    return metaBuilder.build();
                }));
            } else {
                metadataListener.onResponse(metadata);
            }
            metadataListener.whenComplete(
                meta -> repo.finalizeSnapshot(
                    shardGenerations,
                    repositoryData.getGenId(),
                    metadataForSnapshot(entry, meta),
                    snapshotInfo,
                    entry.version(),
                    state -> stateWithoutSnapshot(state, snapshot),
                    ActionListener.wrap(newRepoData -> {
                        completeListenersIgnoringException(endAndGetListenersToResolve(snapshot), Tuple.tuple(newRepoData, snapshotInfo));
                        logger.info("snapshot [{}] completed with state [{}]", snapshot, snapshotInfo.state());
                        runNextQueuedOperation(newRepoData, repository, true);
                    }, e -> handleFinalizationFailure(e, entry, repositoryData))
                ),
                e -> handleFinalizationFailure(e, entry, repositoryData)
            );
        } catch (Exception e) {
            assert false : new AssertionError(e);
            handleFinalizationFailure(e, entry, repositoryData);
        }
    }

    /**
     * Remove a snapshot from {@link #endingSnapshots} set and return its completion listeners that must be resolved.
     */
    private List<ActionListener<Tuple<RepositoryData, SnapshotInfo>>> endAndGetListenersToResolve(Snapshot snapshot) {
        // get listeners before removing from the ending snapshots set to not trip assertion in #assertConsistentWithClusterState that
        // makes sure we don't have listeners for snapshots that aren't tracked in any internal state of this class
        final List<ActionListener<Tuple<RepositoryData, SnapshotInfo>>> listenersToComplete = snapshotCompletionListeners.remove(snapshot);
        endingSnapshots.remove(snapshot);
        return listenersToComplete;
    }

    /**
     * Handles failure to finalize a snapshot. If the exception indicates that this node was unable to publish a cluster state and stopped
     * being the cluster-manager node, then fail all snapshot create and delete listeners executing on this node by delegating to
     * {@link #failAllListenersOnMasterFailOver}. Otherwise, i.e. as a result of failing to write to the snapshot repository for some
     * reason, remove the snapshot's {@link SnapshotsInProgress.Entry} from the cluster state and move on with other queued snapshot
     * operations if there are any.
     *
     * @param e              exception encountered
     * @param entry          snapshot entry that failed to finalize
     * @param repositoryData current repository data for the snapshot's repository
     */
    private void handleFinalizationFailure(Exception e, SnapshotsInProgress.Entry entry, RepositoryData repositoryData) {
        Snapshot snapshot = entry.snapshot();
        if (ExceptionsHelper.unwrap(e, NotClusterManagerException.class, FailedToCommitClusterStateException.class) != null) {
            // Failure due to not being cluster-manager any more, don't try to remove snapshot from cluster state the next cluster-manager
            // will try ending this snapshot again
            logger.debug(() -> new ParameterizedMessage("[{}] failed to update cluster state during snapshot finalization", snapshot), e);
            failSnapshotCompletionListeners(
                snapshot,
                new SnapshotException(snapshot, "Failed to update cluster state during snapshot finalization", e)
            );
            failAllListenersOnMasterFailOver(e);
        } else {
            logger.warn(() -> new ParameterizedMessage("[{}] failed to finalize snapshot", snapshot), e);
            removeFailedSnapshotFromClusterState(snapshot, e, repositoryData, null);
        }
    }

    /**
     * Run the next queued up repository operation for the given repository name.
     *
     * @param repositoryData current repository data
     * @param repository     repository name
     * @param attemptDelete  whether to try and run delete operations that are ready in the cluster state if no
     *                       snapshot create operations remain to execute
     */
    private void runNextQueuedOperation(RepositoryData repositoryData, String repository, boolean attemptDelete) {
        assert currentlyFinalizing.contains(repository);
        final Tuple<SnapshotsInProgress.Entry, Metadata> nextFinalization = repositoryOperations.pollFinalization(repository);
        if (nextFinalization == null) {
            if (attemptDelete) {
                runReadyDeletions(repositoryData, repository);
            } else {
                leaveRepoLoop(repository);
            }
        } else {
            logger.trace("Moving on to finalizing next snapshot [{}]", nextFinalization);
            finalizeSnapshotEntry(nextFinalization.v1(), nextFinalization.v2(), repositoryData);
        }
    }

    /**
     * Runs a cluster state update that checks whether we have outstanding snapshot deletions that can be executed and executes them.
     *
     * TODO: optimize this to execute in a single CS update together with finalizing the latest snapshot
     */
    private void runReadyDeletions(RepositoryData repositoryData, String repository) {
        clusterService.submitStateUpdateTask("Run ready deletions", new ClusterStateUpdateTask() {

            private SnapshotDeletionsInProgress.Entry deletionToRun;

            @Override
            public ClusterState execute(ClusterState currentState) {
                assert readyDeletions(currentState).v1() == currentState
                    : "Deletes should have been set to ready by finished snapshot deletes and finalizations";
                for (SnapshotDeletionsInProgress.Entry entry : currentState.custom(
                    SnapshotDeletionsInProgress.TYPE,
                    SnapshotDeletionsInProgress.EMPTY
                ).getEntries()) {
                    if (entry.repository().equals(repository) && entry.state() == SnapshotDeletionsInProgress.State.STARTED) {
                        deletionToRun = entry;
                        break;
                    }
                }
                return currentState;
            }

            @Override
            public void onFailure(String source, Exception e) {
                logger.warn("Failed to run ready delete operations", e);
                failAllListenersOnMasterFailOver(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (deletionToRun == null) {
                    runNextQueuedOperation(repositoryData, repository, false);
                } else {
                    deleteSnapshotsFromRepository(deletionToRun, repositoryData, newState.nodes().getMinNodeVersion());
                }
            }
        });
    }

    /**
     * Finds snapshot delete operations that are ready to execute in the given {@link ClusterState} and computes a new cluster state that
     * has all executable deletes marked as executing. Returns a {@link Tuple} of the updated cluster state and all executable deletes.
     * This can either be {@link SnapshotDeletionsInProgress.Entry} that were already in state
     * {@link SnapshotDeletionsInProgress.State#STARTED} or waiting entries in state {@link SnapshotDeletionsInProgress.State#WAITING}
     * that were moved to {@link SnapshotDeletionsInProgress.State#STARTED} in the returned updated cluster state.
     *
     * @param currentState current cluster state
     * @return tuple of an updated cluster state and currently executable snapshot delete operations
     */
    private static Tuple<ClusterState, List<SnapshotDeletionsInProgress.Entry>> readyDeletions(ClusterState currentState) {
        final SnapshotDeletionsInProgress deletions = currentState.custom(
            SnapshotDeletionsInProgress.TYPE,
            SnapshotDeletionsInProgress.EMPTY
        );
        if (deletions.hasDeletionsInProgress() == false) {
            return Tuple.tuple(currentState, Collections.emptyList());
        }
        final SnapshotsInProgress snapshotsInProgress = currentState.custom(SnapshotsInProgress.TYPE);
        assert snapshotsInProgress != null;
        final Set<String> repositoriesSeen = new HashSet<>();
        boolean changed = false;
        final ArrayList<SnapshotDeletionsInProgress.Entry> readyDeletions = new ArrayList<>();
        final List<SnapshotDeletionsInProgress.Entry> newDeletes = new ArrayList<>();
        for (SnapshotDeletionsInProgress.Entry entry : deletions.getEntries()) {
            final String repo = entry.repository();
            if (repositoriesSeen.add(entry.repository())
                && entry.state() == SnapshotDeletionsInProgress.State.WAITING
                && snapshotsInProgress.entries()
                    .stream()
                    .filter(se -> se.repository().equals(repo))
                    .noneMatch(SnapshotsService::isWritingToRepository)) {
                changed = true;
                final SnapshotDeletionsInProgress.Entry newEntry = entry.started();
                readyDeletions.add(newEntry);
                newDeletes.add(newEntry);
            } else {
                newDeletes.add(entry);
            }
        }
        return Tuple.tuple(
            changed
                ? ClusterState.builder(currentState)
                    .putCustom(SnapshotDeletionsInProgress.TYPE, SnapshotDeletionsInProgress.of(newDeletes))
                    .build()
                : currentState,
            readyDeletions
        );
    }

    /**
     * Computes the cluster state resulting from removing a given snapshot create operation from the given state.
     *
     * @param state    current cluster state
     * @param snapshot snapshot for which to remove the snapshot operation
     * @return updated cluster state
     */
    private static ClusterState stateWithoutSnapshot(ClusterState state, Snapshot snapshot) {
        SnapshotsInProgress snapshots = state.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
        ClusterState result = state;
        boolean changed = false;
        ArrayList<SnapshotsInProgress.Entry> entries = new ArrayList<>();
        for (SnapshotsInProgress.Entry entry : snapshots.entries()) {
            if (entry.snapshot().equals(snapshot)) {
                changed = true;
            } else {
                entries.add(entry);
            }
        }
        if (changed) {
            result = ClusterState.builder(state)
                .putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.of(unmodifiableList(entries)))
                .build();
        }
        return readyDeletions(result).v1();
    }

    /**
     * Removes record of running snapshot from cluster state and notifies the listener when this action is complete. This method is only
     * used when the snapshot fails for some reason. During normal operation the snapshot repository will remove the
     * {@link SnapshotsInProgress.Entry} from the cluster state once it's done finalizing the snapshot.
     *
     * @param snapshot       snapshot that failed
     * @param failure        exception that failed the snapshot
     * @param repositoryData repository data or {@code null} when cleaning up a BwC snapshot that never fully initialized
     * @param listener       listener to invoke when done with, only passed by the BwC path that has {@code repositoryData} set to
     *                       {@code null}
     */
    private void removeFailedSnapshotFromClusterState(
        Snapshot snapshot,
        Exception failure,
        @Nullable RepositoryData repositoryData,
        @Nullable CleanupAfterErrorListener listener
    ) {
        assert failure != null : "Failure must be supplied";
        clusterService.submitStateUpdateTask("remove snapshot metadata", new ClusterStateUpdateTask() {

            @Override
            public ClusterState execute(ClusterState currentState) {
                final ClusterState updatedState = stateWithoutSnapshot(currentState, snapshot);
                // now check if there are any delete operations that refer to the just failed snapshot and remove the snapshot from them
                return updateWithSnapshots(
                    updatedState,
                    null,
                    deletionsWithoutSnapshots(
                        updatedState.custom(SnapshotDeletionsInProgress.TYPE, SnapshotDeletionsInProgress.EMPTY),
                        Collections.singletonList(snapshot.getSnapshotId()),
                        snapshot.getRepository()
                    )
                );
            }

            @Override
            public void onFailure(String source, Exception e) {
                logger.warn(() -> new ParameterizedMessage("[{}] failed to remove snapshot metadata", snapshot), e);
                failSnapshotCompletionListeners(
                    snapshot,
                    new SnapshotException(snapshot, "Failed to remove snapshot from cluster state", e)
                );
                failAllListenersOnMasterFailOver(e);
                if (listener != null) {
                    listener.onFailure(e);
                }
            }

            @Override
            public void onNoLongerClusterManager(String source) {
                failure.addSuppressed(new SnapshotException(snapshot, "no longer cluster-manager"));
                failSnapshotCompletionListeners(snapshot, failure);
                failAllListenersOnMasterFailOver(new NotClusterManagerException(source));
                if (listener != null) {
                    listener.onNoLongerClusterManager();
                }
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                failSnapshotCompletionListeners(snapshot, failure);
                if (listener == null) {
                    if (repositoryData != null) {
                        runNextQueuedOperation(repositoryData, snapshot.getRepository(), true);
                    }
                } else {
                    listener.onFailure(null);
                }
            }
        });
    }

    /**
     * Remove the given {@link SnapshotId}s for the given {@code repository} from an instance of {@link SnapshotDeletionsInProgress}.
     * If no deletion contained any of the snapshot ids to remove then return {@code null}.
     *
     * @param deletions   snapshot deletions to update
     * @param snapshotIds snapshot ids to remove
     * @param repository  repository that the snapshot ids belong to
     * @return            updated {@link SnapshotDeletionsInProgress} or {@code null} if unchanged
     */
    @Nullable
    private static SnapshotDeletionsInProgress deletionsWithoutSnapshots(
        SnapshotDeletionsInProgress deletions,
        Collection<SnapshotId> snapshotIds,
        String repository
    ) {
        boolean changed = false;
        List<SnapshotDeletionsInProgress.Entry> updatedEntries = new ArrayList<>(deletions.getEntries().size());
        for (SnapshotDeletionsInProgress.Entry entry : deletions.getEntries()) {
            if (entry.repository().equals(repository)) {
                final List<SnapshotId> updatedSnapshotIds = new ArrayList<>(entry.getSnapshots());
                if (updatedSnapshotIds.removeAll(snapshotIds)) {
                    changed = true;
                    updatedEntries.add(entry.withSnapshots(updatedSnapshotIds));
                } else {
                    updatedEntries.add(entry);
                }
            } else {
                updatedEntries.add(entry);
            }
        }
        return changed ? SnapshotDeletionsInProgress.of(updatedEntries) : null;
    }

    private void failSnapshotCompletionListeners(Snapshot snapshot, Exception e) {
        failListenersIgnoringException(endAndGetListenersToResolve(snapshot), e);
        assert repositoryOperations.assertNotQueued(snapshot);
    }

    /**
     * Deletes snapshots from the repository. In-progress snapshots matched by the delete will be aborted before deleting them.
     *
     * @param request         delete snapshot request
     * @param listener        listener
     */
    public void deleteSnapshots(final DeleteSnapshotRequest request, final ActionListener<Void> listener) {

        final String[] snapshotNames = request.snapshots();
        final String repoName = request.repository();
        logger.info(
            () -> new ParameterizedMessage(
                "deleting snapshots [{}] from repository [{}]",
                Strings.arrayToCommaDelimitedString(snapshotNames),
                repoName
            )
        );

        final Repository repository = repositoriesService.repository(repoName);
        repository.executeConsistentStateUpdate(repositoryData -> new ClusterStateUpdateTask(Priority.NORMAL) {

            private Snapshot runningSnapshot;

            private ClusterStateUpdateTask deleteFromRepoTask;

            private boolean abortedDuringInit = false;

            private List<SnapshotId> outstandingDeletes;

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                final Version minNodeVersion = currentState.nodes().getMinNodeVersion();
                if (snapshotNames.length > 1 && minNodeVersion.before(MULTI_DELETE_VERSION)) {
                    throw new IllegalArgumentException(
                        "Deleting multiple snapshots in a single request is only supported in version [ "
                            + MULTI_DELETE_VERSION
                            + "] but cluster contained node of version ["
                            + currentState.nodes().getMinNodeVersion()
                            + "]"
                    );
                }
                final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                final List<SnapshotsInProgress.Entry> snapshotEntries = findInProgressSnapshots(snapshots, snapshotNames, repoName);
                final List<SnapshotId> snapshotIds = matchingSnapshotIds(
                    snapshotEntries.stream().map(e -> e.snapshot().getSnapshotId()).collect(Collectors.toList()),
                    repositoryData,
                    snapshotNames,
                    repoName
                );
                if (snapshotEntries.isEmpty() || minNodeVersion.onOrAfter(SnapshotsService.FULL_CONCURRENCY_VERSION)) {
                    deleteFromRepoTask = createDeleteStateUpdate(snapshotIds, repoName, repositoryData, Priority.NORMAL, listener);
                    return deleteFromRepoTask.execute(currentState);
                }
                assert snapshotEntries.size() == 1 : "Expected just a single running snapshot but saw " + snapshotEntries;
                final SnapshotsInProgress.Entry snapshotEntry = snapshotEntries.get(0);
                runningSnapshot = snapshotEntry.snapshot();
                final ImmutableOpenMap<ShardId, ShardSnapshotStatus> shards;

                final State state = snapshotEntry.state();
                final String failure;

                outstandingDeletes = new ArrayList<>(snapshotIds);
                if (state != State.INIT) {
                    // INIT state snapshots won't ever be physically written to the repository but all other states will end up in the repo
                    outstandingDeletes.add(runningSnapshot.getSnapshotId());
                }
                if (state == State.INIT) {
                    // snapshot is still initializing, mark it as aborted
                    shards = snapshotEntry.shards();
                    assert shards.isEmpty();
                    failure = "Snapshot was aborted during initialization";
                    abortedDuringInit = true;
                } else if (state == State.STARTED) {
                    // snapshot is started - mark every non completed shard as aborted
                    final SnapshotsInProgress.Entry abortedEntry = snapshotEntry.abort();
                    shards = abortedEntry.shards();
                    failure = abortedEntry.failure();
                } else {
                    boolean hasUncompletedShards = false;
                    // Cleanup in case a node gone missing and snapshot wasn't updated for some reason
                    for (ObjectCursor<ShardSnapshotStatus> shardStatus : snapshotEntry.shards().values()) {
                        // Check if we still have shard running on existing nodes
                        if (shardStatus.value.state().completed() == false
                            && shardStatus.value.nodeId() != null
                            && currentState.nodes().get(shardStatus.value.nodeId()) != null) {
                            hasUncompletedShards = true;
                            break;
                        }
                    }
                    if (hasUncompletedShards) {
                        // snapshot is being finalized - wait for shards to complete finalization process
                        logger.debug("trying to delete completed snapshot - should wait for shards to finalize on all nodes");
                        return currentState;
                    } else {
                        // no shards to wait for but a node is gone - this is the only case
                        // where we force to finish the snapshot
                        logger.debug("trying to delete completed snapshot with no finalizing shards - can delete immediately");
                        shards = snapshotEntry.shards();
                    }
                    failure = snapshotEntry.failure();
                }
                return ClusterState.builder(currentState)
                    .putCustom(
                        SnapshotsInProgress.TYPE,
                        SnapshotsInProgress.of(
                            snapshots.entries()
                                .stream()
                                // remove init state snapshot we found from a previous cluster-manager if there was one
                                .filter(existing -> abortedDuringInit == false || existing.equals(snapshotEntry) == false)
                                .map(existing -> {
                                    if (existing.equals(snapshotEntry)) {
                                        return snapshotEntry.fail(shards, State.ABORTED, failure);
                                    }
                                    return existing;
                                })
                                .collect(Collectors.toList())
                        )
                    )
                    .build();
            }

            @Override
            public void onFailure(String source, Exception e) {
                listener.onFailure(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (deleteFromRepoTask != null) {
                    assert outstandingDeletes == null : "Shouldn't have outstanding deletes after already starting delete task";
                    deleteFromRepoTask.clusterStateProcessed(source, oldState, newState);
                    return;
                }
                if (abortedDuringInit) {
                    // BwC Path where we removed an outdated INIT state snapshot from the cluster state
                    logger.info("Successfully aborted snapshot [{}]", runningSnapshot);
                    if (outstandingDeletes.isEmpty()) {
                        listener.onResponse(null);
                    } else {
                        clusterService.submitStateUpdateTask(
                            "delete snapshot",
                            createDeleteStateUpdate(outstandingDeletes, repoName, repositoryData, Priority.IMMEDIATE, listener)
                        );
                    }
                    return;
                }
                logger.trace("adding snapshot completion listener to wait for deleted snapshot to finish");
                addListener(runningSnapshot, ActionListener.wrap(result -> {
                    logger.debug("deleted snapshot completed - deleting files");
                    clusterService.submitStateUpdateTask(
                        "delete snapshot",
                        createDeleteStateUpdate(outstandingDeletes, repoName, result.v1(), Priority.IMMEDIATE, listener)
                    );
                }, e -> {
                    if (ExceptionsHelper.unwrap(e, NotClusterManagerException.class, FailedToCommitClusterStateException.class) != null) {
                        logger.warn("cluster-manager failover before deleted snapshot could complete", e);
                        // Just pass the exception to the transport handler as is so it is retried on the new cluster-manager
                        listener.onFailure(e);
                    } else {
                        logger.warn("deleted snapshot failed", e);
                        listener.onFailure(
                            new SnapshotMissingException(runningSnapshot.getRepository(), runningSnapshot.getSnapshotId(), e)
                        );
                    }
                }));
            }

            @Override
            public TimeValue timeout() {
                return request.clusterManagerNodeTimeout();
            }
        }, "delete snapshot", listener::onFailure);
    }

    private static List<SnapshotId> matchingSnapshotIds(
        List<SnapshotId> inProgress,
        RepositoryData repositoryData,
        String[] snapshotsOrPatterns,
        String repositoryName
    ) {
        final Map<String, SnapshotId> allSnapshotIds = repositoryData.getSnapshotIds()
            .stream()
            .collect(Collectors.toMap(SnapshotId::getName, Function.identity()));
        final Set<SnapshotId> foundSnapshots = new HashSet<>(inProgress);
        for (String snapshotOrPattern : snapshotsOrPatterns) {
            if (Regex.isSimpleMatchPattern(snapshotOrPattern)) {
                for (Map.Entry<String, SnapshotId> entry : allSnapshotIds.entrySet()) {
                    if (Regex.simpleMatch(snapshotOrPattern, entry.getKey())) {
                        foundSnapshots.add(entry.getValue());
                    }
                }
            } else {
                final SnapshotId foundId = allSnapshotIds.get(snapshotOrPattern);
                if (foundId == null) {
                    if (inProgress.stream().noneMatch(snapshotId -> snapshotId.getName().equals(snapshotOrPattern))) {
                        throw new SnapshotMissingException(repositoryName, snapshotOrPattern);
                    }
                } else {
                    foundSnapshots.add(allSnapshotIds.get(snapshotOrPattern));
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(foundSnapshots));
    }

    // Return in-progress snapshot entries by name and repository in the given cluster state or null if none is found
    private static List<SnapshotsInProgress.Entry> findInProgressSnapshots(
        SnapshotsInProgress snapshots,
        String[] snapshotNames,
        String repositoryName
    ) {
        List<SnapshotsInProgress.Entry> entries = new ArrayList<>();
        for (SnapshotsInProgress.Entry entry : snapshots.entries()) {
            if (entry.repository().equals(repositoryName) && Regex.simpleMatch(snapshotNames, entry.snapshot().getSnapshotId().getName())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private ClusterStateUpdateTask createDeleteStateUpdate(
        List<SnapshotId> snapshotIds,
        String repoName,
        RepositoryData repositoryData,
        Priority priority,
        ActionListener<Void> listener
    ) {
        // Short circuit to noop state update if there isn't anything to delete
        if (snapshotIds.isEmpty()) {
            return new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return currentState;
                }

                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    listener.onResponse(null);
                }
            };
        }
        return new ClusterStateUpdateTask(priority) {

            private SnapshotDeletionsInProgress.Entry newDelete;

            private boolean reusedExistingDelete = false;

            // Snapshots that had all of their shard snapshots in queued state and thus were removed from the
            // cluster state right away
            private final Collection<Snapshot> completedNoCleanup = new ArrayList<>();

            // Snapshots that were aborted and that already wrote data to the repository and now have to be deleted
            // from the repository after the cluster state update
            private final Collection<SnapshotsInProgress.Entry> completedWithCleanup = new ArrayList<>();

            @Override
            public ClusterState execute(ClusterState currentState) {
                final SnapshotDeletionsInProgress deletionsInProgress = currentState.custom(
                    SnapshotDeletionsInProgress.TYPE,
                    SnapshotDeletionsInProgress.EMPTY
                );
                final Version minNodeVersion = currentState.nodes().getMinNodeVersion();
                if (minNodeVersion.before(FULL_CONCURRENCY_VERSION)) {
                    if (deletionsInProgress.hasDeletionsInProgress()) {
                        throw new ConcurrentSnapshotExecutionException(
                            new Snapshot(repoName, snapshotIds.get(0)),
                            "cannot delete - another snapshot is currently being deleted in [" + deletionsInProgress + "]"
                        );
                    }
                }
                final RepositoryCleanupInProgress repositoryCleanupInProgress = currentState.custom(
                    RepositoryCleanupInProgress.TYPE,
                    RepositoryCleanupInProgress.EMPTY
                );
                if (repositoryCleanupInProgress.hasCleanupInProgress()) {
                    throw new ConcurrentSnapshotExecutionException(
                        new Snapshot(repoName, snapshotIds.get(0)),
                        "cannot delete snapshots while a repository cleanup is in-progress in [" + repositoryCleanupInProgress + "]"
                    );
                }
                final RestoreInProgress restoreInProgress = currentState.custom(RestoreInProgress.TYPE, RestoreInProgress.EMPTY);
                // don't allow snapshot deletions while a restore is taking place,
                // otherwise we could end up deleting a snapshot that is being restored
                // and the files the restore depends on would all be gone

                for (RestoreInProgress.Entry entry : restoreInProgress) {
                    if (repoName.equals(entry.snapshot().getRepository()) && snapshotIds.contains(entry.snapshot().getSnapshotId())) {
                        throw new ConcurrentSnapshotExecutionException(
                            new Snapshot(repoName, snapshotIds.get(0)),
                            "cannot delete snapshot during a restore in progress in [" + restoreInProgress + "]"
                        );
                    }
                }
                final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
                final Set<SnapshotId> activeCloneSources = snapshots.entries()
                    .stream()
                    .filter(SnapshotsInProgress.Entry::isClone)
                    .map(SnapshotsInProgress.Entry::source)
                    .collect(Collectors.toSet());
                for (SnapshotId snapshotId : snapshotIds) {
                    if (activeCloneSources.contains(snapshotId)) {
                        throw new ConcurrentSnapshotExecutionException(
                            new Snapshot(repoName, snapshotId),
                            "cannot delete snapshot while it is being cloned"
                        );
                    }
                }
                // Snapshot ids that will have to be physically deleted from the repository
                final Set<SnapshotId> snapshotIdsRequiringCleanup = new HashSet<>(snapshotIds);
                final SnapshotsInProgress updatedSnapshots;
                if (minNodeVersion.onOrAfter(FULL_CONCURRENCY_VERSION)) {
                    updatedSnapshots = SnapshotsInProgress.of(snapshots.entries().stream().map(existing -> {
                        if (existing.state() == State.STARTED
                            && snapshotIdsRequiringCleanup.contains(existing.snapshot().getSnapshotId())) {
                            // snapshot is started - mark every non completed shard as aborted
                            final SnapshotsInProgress.Entry abortedEntry = existing.abort();
                            if (abortedEntry == null) {
                                // No work has been done for this snapshot yet so we remove it from the cluster state directly
                                final Snapshot existingNotYetStartedSnapshot = existing.snapshot();
                                // Adding the snapshot to #endingSnapshots since we still have to resolve its listeners to not trip
                                // any leaked listener assertions
                                if (endingSnapshots.add(existingNotYetStartedSnapshot)) {
                                    completedNoCleanup.add(existingNotYetStartedSnapshot);
                                }
                                snapshotIdsRequiringCleanup.remove(existingNotYetStartedSnapshot.getSnapshotId());
                            } else if (abortedEntry.state().completed()) {
                                completedWithCleanup.add(abortedEntry);
                            }
                            return abortedEntry;
                        }
                        return existing;
                    }).filter(Objects::nonNull).collect(Collectors.toList()));
                    if (snapshotIdsRequiringCleanup.isEmpty()) {
                        // We only saw snapshots that could be removed from the cluster state right away, no need to update the deletions
                        return updateWithSnapshots(currentState, updatedSnapshots, null);
                    }
                } else {
                    if (snapshots.entries().isEmpty() == false) {
                        // However other snapshots are running - cannot continue
                        throw new ConcurrentSnapshotExecutionException(
                            repoName,
                            snapshotIds.toString(),
                            "another snapshot is currently running cannot delete"
                        );
                    }
                    updatedSnapshots = snapshots;
                }
                // add the snapshot deletion to the cluster state
                final SnapshotDeletionsInProgress.Entry replacedEntry = deletionsInProgress.getEntries()
                    .stream()
                    .filter(entry -> entry.repository().equals(repoName) && entry.state() == SnapshotDeletionsInProgress.State.WAITING)
                    .findFirst()
                    .orElse(null);
                if (replacedEntry == null) {
                    final Optional<SnapshotDeletionsInProgress.Entry> foundDuplicate = deletionsInProgress.getEntries()
                        .stream()
                        .filter(
                            entry -> entry.repository().equals(repoName)
                                && entry.state() == SnapshotDeletionsInProgress.State.STARTED
                                && entry.getSnapshots().containsAll(snapshotIds)
                        )
                        .findFirst();
                    if (foundDuplicate.isPresent()) {
                        newDelete = foundDuplicate.get();
                        reusedExistingDelete = true;
                        return currentState;
                    }
                    final List<SnapshotId> toDelete = Collections.unmodifiableList(new ArrayList<>(snapshotIdsRequiringCleanup));
                    ensureBelowConcurrencyLimit(repoName, toDelete.get(0).getName(), snapshots, deletionsInProgress);
                    newDelete = new SnapshotDeletionsInProgress.Entry(
                        toDelete,
                        repoName,
                        threadPool.absoluteTimeInMillis(),
                        repositoryData.getGenId(),
                        updatedSnapshots.entries()
                            .stream()
                            .filter(entry -> repoName.equals(entry.repository()))
                            .noneMatch(SnapshotsService::isWritingToRepository)
                            && deletionsInProgress.getEntries()
                                .stream()
                                .noneMatch(
                                    entry -> repoName.equals(entry.repository())
                                        && entry.state() == SnapshotDeletionsInProgress.State.STARTED
                                ) ? SnapshotDeletionsInProgress.State.STARTED : SnapshotDeletionsInProgress.State.WAITING
                    );
                } else {
                    newDelete = replacedEntry.withAddedSnapshots(snapshotIdsRequiringCleanup);
                }
                return updateWithSnapshots(
                    currentState,
                    updatedSnapshots,
                    (replacedEntry == null ? deletionsInProgress : deletionsInProgress.withRemovedEntry(replacedEntry.uuid()))
                        .withAddedEntry(newDelete)
                );
            }

            @Override
            public void onFailure(String source, Exception e) {
                endingSnapshots.removeAll(completedNoCleanup);
                listener.onFailure(e);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (completedNoCleanup.isEmpty() == false) {
                    logger.info("snapshots {} aborted", completedNoCleanup);
                }
                for (Snapshot snapshot : completedNoCleanup) {
                    failSnapshotCompletionListeners(snapshot, new SnapshotException(snapshot, SnapshotsInProgress.ABORTED_FAILURE_TEXT));
                }
                if (newDelete == null) {
                    listener.onResponse(null);
                } else {
                    addDeleteListener(newDelete.uuid(), listener);
                    if (reusedExistingDelete) {
                        return;
                    }
                    if (newDelete.state() == SnapshotDeletionsInProgress.State.STARTED) {
                        if (tryEnterRepoLoop(repoName)) {
                            deleteSnapshotsFromRepository(newDelete, repositoryData, newState.nodes().getMinNodeVersion());
                        } else {
                            logger.trace("Delete [{}] could not execute directly and was queued", newDelete);
                        }
                    } else {
                        for (SnapshotsInProgress.Entry completedSnapshot : completedWithCleanup) {
                            endSnapshot(completedSnapshot, newState.metadata(), repositoryData);
                        }
                    }
                }
            }
        };
    }

    /**
     * Checks if the given {@link SnapshotsInProgress.Entry} is currently writing to the repository.
     *
     * @param entry snapshot entry
     * @return true if entry is currently writing to the repository
     */
    private static boolean isWritingToRepository(SnapshotsInProgress.Entry entry) {
        if (entry.state().completed()) {
            // Entry is writing to the repo because it's finalizing on cluster-manager
            return true;
        }
        for (ObjectCursor<ShardSnapshotStatus> value : entry.shards().values()) {
            if (value.value.isActive()) {
                // Entry is writing to the repo because it's writing to a shard on a data node or waiting to do so for a concrete shard
                return true;
            }
        }
        return false;
    }

    private void addDeleteListener(String deleteUUID, ActionListener<Void> listener) {
        snapshotDeletionListeners.computeIfAbsent(deleteUUID, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Determines the minimum {@link Version} that the snapshot repository must be compatible with from the current nodes in the cluster
     * and the contents of the repository. The minimum version is determined as the lowest version found across all snapshots in the
     * repository and all nodes in the cluster.
     *
     * @param minNodeVersion minimum node version in the cluster
     * @param repositoryData current {@link RepositoryData} of that repository
     * @param excluded       snapshot id to ignore when computing the minimum version
     *                       (used to use newer metadata version after a snapshot delete)
     * @return minimum node version that must still be able to read the repository metadata
     */
    public Version minCompatibleVersion(Version minNodeVersion, RepositoryData repositoryData, @Nullable Collection<SnapshotId> excluded) {
        Version minCompatVersion = minNodeVersion;
        final Collection<SnapshotId> snapshotIds = repositoryData.getSnapshotIds();
        for (SnapshotId snapshotId : snapshotIds.stream()
            .filter(excluded == null ? sn -> true : sn -> excluded.contains(sn) == false)
            .collect(Collectors.toList())) {
            final Version known = repositoryData.getVersion(snapshotId);
            // If we don't have the version cached in the repository data yet we load it from the snapshot info blobs
            if (known == null) {
                assert repositoryData.shardGenerations().totalShards() == 0 : "Saw shard generations ["
                    + repositoryData.shardGenerations()
                    + "] but did not have versions tracked for snapshot ["
                    + snapshotId
                    + "]";
                return OLD_SNAPSHOT_FORMAT;
            } else {
                minCompatVersion = minCompatVersion.before(known) ? minCompatVersion : known;
            }
        }
        return minCompatVersion;
    }

    /**
     * Checks whether the metadata version supports writing {@link ShardGenerations} to the repository.
     *
     * @param repositoryMetaVersion version to check
     * @return true if version supports {@link ShardGenerations}
     */
    public static boolean useShardGenerations(Version repositoryMetaVersion) {
        return repositoryMetaVersion.onOrAfter(SHARD_GEN_IN_REPO_DATA_VERSION);
    }

    /**
     * Checks whether the metadata version supports writing {@link ShardGenerations} to the repository.
     *
     * @param repositoryMetaVersion version to check
     * @return true if version supports {@link ShardGenerations}
     */
    public static boolean useIndexGenerations(Version repositoryMetaVersion) {
        return repositoryMetaVersion.onOrAfter(INDEX_GEN_IN_REPO_DATA_VERSION);
    }

    /** Deletes snapshot from repository
     *
     * @param deleteEntry       delete entry in cluster state
     * @param minNodeVersion    minimum node version in the cluster
     */
    private void deleteSnapshotsFromRepository(SnapshotDeletionsInProgress.Entry deleteEntry, Version minNodeVersion) {
        final long expectedRepoGen = deleteEntry.repositoryStateId();
        repositoriesService.getRepositoryData(deleteEntry.repository(), new ActionListener<RepositoryData>() {
            @Override
            public void onResponse(RepositoryData repositoryData) {
                assert repositoryData.getGenId() == expectedRepoGen
                    : "Repository generation should not change as long as a ready delete is found in the cluster state but found ["
                        + expectedRepoGen
                        + "] in cluster state and ["
                        + repositoryData.getGenId()
                        + "] in the repository";
                deleteSnapshotsFromRepository(deleteEntry, repositoryData, minNodeVersion);
            }

            @Override
            public void onFailure(Exception e) {
                clusterService.submitStateUpdateTask(
                    "fail repo tasks for [" + deleteEntry.repository() + "]",
                    new FailPendingRepoTasksTask(deleteEntry.repository(), e)
                );
            }
        });
    }

    /** Deletes snapshot from repository
     *
     * @param deleteEntry       delete entry in cluster state
     * @param repositoryData    the {@link RepositoryData} of the repository to delete from
     * @param minNodeVersion    minimum node version in the cluster
     */
    private void deleteSnapshotsFromRepository(
        SnapshotDeletionsInProgress.Entry deleteEntry,
        RepositoryData repositoryData,
        Version minNodeVersion
    ) {
        if (repositoryOperations.startDeletion(deleteEntry.uuid())) {
            assert currentlyFinalizing.contains(deleteEntry.repository());
            final List<SnapshotId> snapshotIds = deleteEntry.getSnapshots();
            assert deleteEntry.state() == SnapshotDeletionsInProgress.State.STARTED : "incorrect state for entry [" + deleteEntry + "]";
            repositoriesService.repository(deleteEntry.repository())
                .deleteSnapshots(
                    snapshotIds,
                    repositoryData.getGenId(),
                    minCompatibleVersion(minNodeVersion, repositoryData, snapshotIds),
                    ActionListener.wrap(updatedRepoData -> {
                        logger.info("snapshots {} deleted", snapshotIds);
                        removeSnapshotDeletionFromClusterState(deleteEntry, null, updatedRepoData);
                    }, ex -> removeSnapshotDeletionFromClusterState(deleteEntry, ex, repositoryData))
                );
        }
    }

    /**
     * Removes a {@link SnapshotDeletionsInProgress.Entry} from {@link SnapshotDeletionsInProgress} in the cluster state after it executed
     * on the repository.
     *
     * @param deleteEntry delete entry to remove from the cluster state
     * @param failure     failure encountered while executing the delete on the repository or {@code null} if the delete executed
     *                    successfully
     * @param repositoryData current {@link RepositoryData} for the repository we just ran the delete on.
     */
    private void removeSnapshotDeletionFromClusterState(
        final SnapshotDeletionsInProgress.Entry deleteEntry,
        @Nullable final Exception failure,
        final RepositoryData repositoryData
    ) {
        final ClusterStateUpdateTask clusterStateUpdateTask;
        if (failure == null) {
            // If we didn't have a failure during the snapshot delete we will remove all snapshot ids that the delete successfully removed
            // from the repository from enqueued snapshot delete entries during the cluster state update. After the cluster state update we
            // resolve the delete listeners with the latest repository data from after the delete.
            clusterStateUpdateTask = new RemoveSnapshotDeletionAndContinueTask(deleteEntry, repositoryData) {
                @Override
                protected SnapshotDeletionsInProgress filterDeletions(SnapshotDeletionsInProgress deletions) {
                    final SnapshotDeletionsInProgress updatedDeletions = deletionsWithoutSnapshots(
                        deletions,
                        deleteEntry.getSnapshots(),
                        deleteEntry.repository()
                    );
                    return updatedDeletions == null ? deletions : updatedDeletions;
                }

                @Override
                protected void handleListeners(List<ActionListener<Void>> deleteListeners) {
                    assert repositoryData.getSnapshotIds().stream().noneMatch(deleteEntry.getSnapshots()::contains)
                        : "Repository data contained snapshot ids "
                            + repositoryData.getSnapshotIds()
                            + " that should should been deleted by ["
                            + deleteEntry
                            + "]";
                    completeListenersIgnoringException(deleteListeners, null);
                }
            };
        } else {
            // The delete failed to execute on the repository. We remove it from the cluster state and then fail all listeners associated
            // with it.
            clusterStateUpdateTask = new RemoveSnapshotDeletionAndContinueTask(deleteEntry, repositoryData) {
                @Override
                protected void handleListeners(List<ActionListener<Void>> deleteListeners) {
                    failListenersIgnoringException(deleteListeners, failure);
                }
            };
        }
        clusterService.submitStateUpdateTask("remove snapshot deletion metadata", clusterStateUpdateTask);
    }

    /**
     * Handle snapshot or delete failure due to not being cluster-manager any more so we don't try to do run additional cluster state updates.
     * The next cluster-manager will try handling the missing operations. All we can do is fail all the listeners on this cluster-manager node so that
     * transport requests return and we don't leak listeners.
     *
     * @param e exception that caused us to realize we are not cluster-manager any longer
     */
    private void failAllListenersOnMasterFailOver(Exception e) {
        logger.debug("Failing all snapshot operation listeners because this node is not cluster-manager any longer", e);
        synchronized (currentlyFinalizing) {
            if (ExceptionsHelper.unwrap(e, NotClusterManagerException.class, FailedToCommitClusterStateException.class) != null) {
                repositoryOperations.clear();
                for (Snapshot snapshot : new HashSet<>(snapshotCompletionListeners.keySet())) {
                    failSnapshotCompletionListeners(snapshot, new SnapshotException(snapshot, "no longer cluster-manager"));
                }
                final Exception wrapped = new RepositoryException("_all", "Failed to update cluster state during repository operation", e);
                for (Iterator<List<ActionListener<Void>>> iterator = snapshotDeletionListeners.values().iterator(); iterator.hasNext();) {
                    final List<ActionListener<Void>> listeners = iterator.next();
                    iterator.remove();
                    failListenersIgnoringException(listeners, wrapped);
                }
                assert snapshotDeletionListeners.isEmpty() : "No new listeners should have been added but saw " + snapshotDeletionListeners;
            } else {
                assert false : new AssertionError(
                    "Modifying snapshot state should only ever fail because we failed to publish new state",
                    e
                );
                logger.error("Unexpected failure during cluster state update", e);
            }
            currentlyFinalizing.clear();
        }
    }

    /**
     * A cluster state update that will remove a given {@link SnapshotDeletionsInProgress.Entry} from the cluster state
     * and trigger running the next snapshot-delete or -finalization operation available to execute if there is one
     * ready in the cluster state as a result of this state update.
     */
    private abstract class RemoveSnapshotDeletionAndContinueTask extends ClusterStateUpdateTask {

        // Snapshots that can be finalized after the delete operation has been removed from the cluster state
        protected final List<SnapshotsInProgress.Entry> newFinalizations = new ArrayList<>();

        private List<SnapshotDeletionsInProgress.Entry> readyDeletions = Collections.emptyList();

        protected final SnapshotDeletionsInProgress.Entry deleteEntry;

        private final RepositoryData repositoryData;

        RemoveSnapshotDeletionAndContinueTask(SnapshotDeletionsInProgress.Entry deleteEntry, RepositoryData repositoryData) {
            this.deleteEntry = deleteEntry;
            this.repositoryData = repositoryData;
        }

        @Override
        public ClusterState execute(ClusterState currentState) {
            final SnapshotDeletionsInProgress deletions = currentState.custom(SnapshotDeletionsInProgress.TYPE);
            assert deletions != null : "We only run this if there were deletions in the cluster state before";
            final SnapshotDeletionsInProgress updatedDeletions = deletions.withRemovedEntry(deleteEntry.uuid());
            if (updatedDeletions == deletions) {
                return currentState;
            }
            final SnapshotDeletionsInProgress newDeletions = filterDeletions(updatedDeletions);
            final Tuple<ClusterState, List<SnapshotDeletionsInProgress.Entry>> res = readyDeletions(
                updateWithSnapshots(currentState, updatedSnapshotsInProgress(currentState, newDeletions), newDeletions)
            );
            readyDeletions = res.v2();
            return res.v1();
        }

        @Override
        public void onFailure(String source, Exception e) {
            logger.warn(() -> new ParameterizedMessage("{} failed to remove snapshot deletion metadata", deleteEntry), e);
            repositoryOperations.finishDeletion(deleteEntry.uuid());
            failAllListenersOnMasterFailOver(e);
        }

        protected SnapshotDeletionsInProgress filterDeletions(SnapshotDeletionsInProgress deletions) {
            return deletions;
        }

        @Override
        public final void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
            final List<ActionListener<Void>> deleteListeners;
            repositoryOperations.finishDeletion(deleteEntry.uuid());
            deleteListeners = snapshotDeletionListeners.remove(deleteEntry.uuid());
            handleListeners(deleteListeners);
            if (newFinalizations.isEmpty()) {
                if (readyDeletions.isEmpty()) {
                    leaveRepoLoop(deleteEntry.repository());
                } else {
                    for (SnapshotDeletionsInProgress.Entry readyDeletion : readyDeletions) {
                        deleteSnapshotsFromRepository(readyDeletion, repositoryData, newState.nodes().getMinNodeVersion());
                    }
                }
            } else {
                leaveRepoLoop(deleteEntry.repository());
                assert readyDeletions.stream().noneMatch(entry -> entry.repository().equals(deleteEntry.repository()))
                    : "New finalizations " + newFinalizations + " added even though deletes " + readyDeletions + " are ready";
                for (SnapshotsInProgress.Entry entry : newFinalizations) {
                    endSnapshot(entry, newState.metadata(), repositoryData);
                }
            }
        }

        /**
         * Invoke snapshot delete listeners for {@link #deleteEntry}.
         *
         * @param deleteListeners delete snapshot listeners or {@code null} if there weren't any for {@link #deleteEntry}.
         */
        protected abstract void handleListeners(@Nullable List<ActionListener<Void>> deleteListeners);

        /**
         * Computes an updated {@link SnapshotsInProgress} that takes into account an updated version of
         * {@link SnapshotDeletionsInProgress} that has a {@link SnapshotDeletionsInProgress.Entry} removed from it
         * relative to the {@link SnapshotDeletionsInProgress} found in {@code currentState}.
         * The removal of a delete from the cluster state can trigger two possible actions on in-progress snapshots:
         * <ul>
         *     <li>Snapshots that had unfinished shard snapshots in state {@link ShardSnapshotStatus#UNASSIGNED_QUEUED} that
         *     could not be started because the delete was running can have those started.</li>
         *     <li>Snapshots that had all their shards reach a completed state while a delete was running (e.g. as a result of
         *     nodes dropping out of the cluster or another incoming delete aborting them) need not be updated in the cluster
         *     state but need to have their finalization triggered now that it's possible with the removal of the delete
         *     from the state.</li>
         * </ul>
         *
         * @param currentState     current cluster state
         * @param updatedDeletions deletions with removed entry
         * @return updated snapshot in progress instance or {@code null} if there are no changes to it
         */
        @Nullable
        private SnapshotsInProgress updatedSnapshotsInProgress(ClusterState currentState, SnapshotDeletionsInProgress updatedDeletions) {
            final SnapshotsInProgress snapshotsInProgress = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
            final List<SnapshotsInProgress.Entry> snapshotEntries = new ArrayList<>();

            // Keep track of shardIds that we started snapshots for as a result of removing this delete so we don't assign
            // them to multiple snapshots by accident
            final Set<ShardId> reassignedShardIds = new HashSet<>();

            boolean changed = false;

            final String repoName = deleteEntry.repository();
            // Computing the new assignments can be quite costly, only do it once below if actually needed
            ImmutableOpenMap<ShardId, ShardSnapshotStatus> shardAssignments = null;
            for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
                if (entry.repository().equals(repoName)) {
                    if (entry.state().completed() == false) {
                        // Collect waiting shards that in entry that we can assign now that we are done with the deletion
                        final List<ShardId> canBeUpdated = new ArrayList<>();
                        for (ObjectObjectCursor<ShardId, ShardSnapshotStatus> value : entry.shards()) {
                            if (value.value.equals(ShardSnapshotStatus.UNASSIGNED_QUEUED)
                                && reassignedShardIds.contains(value.key) == false) {
                                canBeUpdated.add(value.key);
                            }
                        }
                        if (canBeUpdated.isEmpty()) {
                            // No shards can be updated in this snapshot so we just add it as is again
                            snapshotEntries.add(entry);
                        } else {
                            if (shardAssignments == null) {
                                shardAssignments = shards(
                                    snapshotsInProgress,
                                    updatedDeletions,
                                    currentState.metadata(),
                                    currentState.routingTable(),
                                    entry.indices(),
                                    entry.version().onOrAfter(SHARD_GEN_IN_REPO_DATA_VERSION),
                                    repositoryData,
                                    repoName
                                );
                            }
                            final ImmutableOpenMap.Builder<ShardId, ShardSnapshotStatus> updatedAssignmentsBuilder = ImmutableOpenMap
                                .builder(entry.shards());
                            for (ShardId shardId : canBeUpdated) {
                                final ShardSnapshotStatus updated = shardAssignments.get(shardId);
                                if (updated == null) {
                                    // We don't have a new assignment for this shard because its index was concurrently deleted
                                    assert currentState.routingTable().hasIndex(shardId.getIndex()) == false : "Missing assignment for ["
                                        + shardId
                                        + "]";
                                    updatedAssignmentsBuilder.put(shardId, ShardSnapshotStatus.MISSING);
                                } else {
                                    final boolean added = reassignedShardIds.add(shardId);
                                    assert added;
                                    updatedAssignmentsBuilder.put(shardId, updated);
                                }
                            }
                            final SnapshotsInProgress.Entry updatedEntry = entry.withShardStates(updatedAssignmentsBuilder.build());
                            snapshotEntries.add(updatedEntry);
                            changed = true;
                            // When all the required shards for a snapshot are missing, the snapshot state will be "completed"
                            // need to finalize it.
                            if (updatedEntry.state().completed()) {
                                newFinalizations.add(entry);
                            }
                        }
                    } else {
                        // Entry is already completed so we will finalize it now that the delete doesn't block us after
                        // this CS update finishes
                        newFinalizations.add(entry);
                        snapshotEntries.add(entry);
                    }
                } else {
                    // Entry is for another repository we just keep it as is
                    snapshotEntries.add(entry);
                }
            }
            return changed ? SnapshotsInProgress.of(snapshotEntries) : null;
        }
    }

    /**
     * Shortcut to build new {@link ClusterState} from the current state and updated values of {@link SnapshotsInProgress} and
     * {@link SnapshotDeletionsInProgress}.
     *
     * @param state                       current cluster state
     * @param snapshotsInProgress         new value for {@link SnapshotsInProgress} or {@code null} if it's unchanged
     * @param snapshotDeletionsInProgress new value for {@link SnapshotDeletionsInProgress} or {@code null} if it's unchanged
     * @return updated cluster state
     */
    public static ClusterState updateWithSnapshots(
        ClusterState state,
        @Nullable SnapshotsInProgress snapshotsInProgress,
        @Nullable SnapshotDeletionsInProgress snapshotDeletionsInProgress
    ) {
        if (snapshotsInProgress == null && snapshotDeletionsInProgress == null) {
            return state;
        }
        ClusterState.Builder builder = ClusterState.builder(state);
        if (snapshotsInProgress != null) {
            builder.putCustom(SnapshotsInProgress.TYPE, snapshotsInProgress);
        }
        if (snapshotDeletionsInProgress != null) {
            builder.putCustom(SnapshotDeletionsInProgress.TYPE, snapshotDeletionsInProgress);
        }
        return builder.build();
    }

    private static <T> void failListenersIgnoringException(@Nullable List<ActionListener<T>> listeners, Exception failure) {
        if (listeners != null) {
            try {
                ActionListener.onFailure(listeners, failure);
            } catch (Exception ex) {
                assert false : new AssertionError(ex);
                logger.warn("Failed to notify listeners", ex);
            }
        }
    }

    private static <T> void completeListenersIgnoringException(@Nullable List<ActionListener<T>> listeners, T result) {
        if (listeners != null) {
            try {
                ActionListener.onResponse(listeners, result);
            } catch (Exception ex) {
                assert false : new AssertionError(ex);
                logger.warn("Failed to notify listeners", ex);
            }
        }
    }

    /**
     * Calculates the assignment of shards to data nodes for a new snapshot based on the given cluster state and the
     * indices that should be included in the snapshot.
     *
     * @param indices             Indices to snapshot
     * @param useShardGenerations whether to write {@link ShardGenerations} during the snapshot
     * @return list of shard to be included into current snapshot
     */
    private static ImmutableOpenMap<ShardId, SnapshotsInProgress.ShardSnapshotStatus> shards(
        SnapshotsInProgress snapshotsInProgress,
        @Nullable SnapshotDeletionsInProgress deletionsInProgress,
        Metadata metadata,
        RoutingTable routingTable,
        List<IndexId> indices,
        boolean useShardGenerations,
        RepositoryData repositoryData,
        String repoName
    ) {
        ImmutableOpenMap.Builder<ShardId, SnapshotsInProgress.ShardSnapshotStatus> builder = ImmutableOpenMap.builder();
        final ShardGenerations shardGenerations = repositoryData.shardGenerations();
        final InFlightShardSnapshotStates inFlightShardStates = InFlightShardSnapshotStates.forRepo(
            repoName,
            snapshotsInProgress.entries()
        );
        final boolean readyToExecute = deletionsInProgress == null
            || deletionsInProgress.getEntries()
                .stream()
                .noneMatch(entry -> entry.repository().equals(repoName) && entry.state() == SnapshotDeletionsInProgress.State.STARTED);
        for (IndexId index : indices) {
            final String indexName = index.getName();
            final boolean isNewIndex = repositoryData.getIndices().containsKey(indexName) == false;
            IndexMetadata indexMetadata = metadata.index(indexName);
            if (indexMetadata == null) {
                // The index was deleted before we managed to start the snapshot - mark it as missing.
                builder.put(new ShardId(indexName, IndexMetadata.INDEX_UUID_NA_VALUE, 0), ShardSnapshotStatus.MISSING);
            } else {
                final IndexRoutingTable indexRoutingTable = routingTable.index(indexName);
                for (int i = 0; i < indexMetadata.getNumberOfShards(); i++) {
                    final ShardId shardId = indexRoutingTable.shard(i).shardId();
                    final String shardRepoGeneration;
                    if (useShardGenerations) {
                        final String inFlightGeneration = inFlightShardStates.generationForShard(index, shardId.id(), shardGenerations);
                        if (inFlightGeneration == null && isNewIndex) {
                            assert shardGenerations.getShardGen(index, shardId.getId()) == null : "Found shard generation for new index ["
                                + index
                                + "]";
                            shardRepoGeneration = ShardGenerations.NEW_SHARD_GEN;
                        } else {
                            shardRepoGeneration = inFlightGeneration;
                        }
                    } else {
                        shardRepoGeneration = null;
                    }
                    final ShardSnapshotStatus shardSnapshotStatus;
                    if (indexRoutingTable == null) {
                        shardSnapshotStatus = new SnapshotsInProgress.ShardSnapshotStatus(
                            null,
                            ShardState.MISSING,
                            "missing routing table",
                            shardRepoGeneration
                        );
                    } else {
                        ShardRouting primary = indexRoutingTable.shard(i).primaryShard();
                        if (readyToExecute == false || inFlightShardStates.isActive(indexName, i)) {
                            shardSnapshotStatus = ShardSnapshotStatus.UNASSIGNED_QUEUED;
                        } else if (primary == null || !primary.assignedToNode()) {
                            shardSnapshotStatus = new ShardSnapshotStatus(
                                null,
                                ShardState.MISSING,
                                "primary shard is not allocated",
                                shardRepoGeneration
                            );
                        } else if (primary.relocating() || primary.initializing()) {
                            shardSnapshotStatus = new ShardSnapshotStatus(primary.currentNodeId(), ShardState.WAITING, shardRepoGeneration);
                        } else if (!primary.started()) {
                            shardSnapshotStatus = new ShardSnapshotStatus(
                                primary.currentNodeId(),
                                ShardState.MISSING,
                                "primary shard hasn't been started yet",
                                shardRepoGeneration
                            );
                        } else {
                            shardSnapshotStatus = new ShardSnapshotStatus(primary.currentNodeId(), shardRepoGeneration);
                        }
                    }
                    builder.put(shardId, shardSnapshotStatus);
                }
            }
        }

        return builder.build();
    }

    /**
     * Returns the data streams that are currently being snapshotted (with partial == false) and that are contained in the
     * indices-to-check set.
     */
    public static Set<String> snapshottingDataStreams(final ClusterState currentState, final Set<String> dataStreamsToCheck) {
        final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE);
        if (snapshots == null) {
            return emptySet();
        }

        Map<String, DataStream> dataStreams = currentState.metadata().dataStreams();
        return snapshots.entries()
            .stream()
            .filter(e -> e.partial() == false)
            .flatMap(e -> e.dataStreams().stream())
            .filter(ds -> dataStreams.containsKey(ds) && dataStreamsToCheck.contains(ds))
            .collect(Collectors.toSet());
    }

    /**
     * Returns the indices that are currently being snapshotted (with partial == false) and that are contained in the indices-to-check set.
     */
    public static Set<Index> snapshottingIndices(final ClusterState currentState, final Set<Index> indicesToCheck) {
        final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE);
        if (snapshots == null) {
            return emptySet();
        }

        final Set<Index> indices = new HashSet<>();
        for (final SnapshotsInProgress.Entry entry : snapshots.entries()) {
            if (entry.partial() == false) {
                for (IndexId index : entry.indices()) {
                    IndexMetadata indexMetadata = currentState.metadata().index(index.getName());
                    if (indexMetadata != null && indicesToCheck.contains(indexMetadata.getIndex())) {
                        indices.add(indexMetadata.getIndex());
                    }
                }
            }
        }
        return indices;
    }

    /**
     * Adds snapshot completion listener
     *
     * @param snapshot Snapshot to listen for
     * @param listener listener
     */
    private void addListener(Snapshot snapshot, ActionListener<Tuple<RepositoryData, SnapshotInfo>> listener) {
        snapshotCompletionListeners.computeIfAbsent(snapshot, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    protected void doStart() {
        assert this.updateSnapshotStatusHandler != null;
        assert transportService.getRequestHandler(UPDATE_SNAPSHOT_STATUS_ACTION_NAME) != null;
    }

    @Override
    protected void doStop() {

    }

    @Override
    protected void doClose() {
        clusterService.removeApplier(this);
    }

    /**
     * Assert that no in-memory state for any running snapshot-create or -delete operation exists in this instance.
     */
    public boolean assertAllListenersResolved() {
        final DiscoveryNode localNode = clusterService.localNode();
        assert endingSnapshots.isEmpty() : "Found leaked ending snapshots " + endingSnapshots + " on [" + localNode + "]";
        assert snapshotCompletionListeners.isEmpty() : "Found leaked snapshot completion listeners "
            + snapshotCompletionListeners
            + " on ["
            + localNode
            + "]";
        assert currentlyFinalizing.isEmpty() : "Found leaked finalizations " + currentlyFinalizing + " on [" + localNode + "]";
        assert snapshotDeletionListeners.isEmpty() : "Found leaked snapshot delete listeners "
            + snapshotDeletionListeners
            + " on ["
            + localNode
            + "]";
        assert repositoryOperations.isEmpty() : "Found leaked snapshots to finalize " + repositoryOperations + " on [" + localNode + "]";
        return true;
    }

    /**
     * Executor that applies {@link ShardSnapshotUpdate}s to the current cluster state. The algorithm implemented below works as described
     * below:
     * Every shard snapshot or clone state update can result in multiple snapshots being updated. In order to determine whether or not a
     * shard update has an effect we use an outer loop over all current executing snapshot operations that iterates over them in the order
     * they were started in and an inner loop over the list of shard update tasks.
     *
     * If the inner loop finds that a shard update task applies to a given snapshot and either a shard-snapshot or shard-clone operation in
     * it then it will update the state of the snapshot entry accordingly. If that update was a noop, then the task is removed from the
     * iteration as it was already applied before and likely just arrived on the cluster-manager node again due to retries upstream.
     * If the update was not a noop, then it means that the shard it applied to is now available for another snapshot or clone operation
     * to be re-assigned if there is another snapshot operation that is waiting for the shard to become available. We therefore record the
     * fact that a task was executed by adding it to a collection of executed tasks. If a subsequent execution of the outer loop finds that
     * a task in the executed tasks collection applied to a shard it was waiting for to become available, then the shard snapshot operation
     * will be started for that snapshot entry and the task removed from the collection of tasks that need to be applied to snapshot
     * entries since it can not have any further effects.
     *
     * Package private to allow for tests.
     */
    static final ClusterStateTaskExecutor<ShardSnapshotUpdate> SHARD_STATE_EXECUTOR = (currentState, tasks) -> {
        int changedCount = 0;
        int startedCount = 0;
        final List<SnapshotsInProgress.Entry> entries = new ArrayList<>();
        final String localNodeId = currentState.nodes().getLocalNodeId();
        // Tasks to check for updates for running snapshots.
        final List<ShardSnapshotUpdate> unconsumedTasks = new ArrayList<>(tasks);
        // Tasks that were used to complete an existing in-progress shard snapshot
        final Set<ShardSnapshotUpdate> executedTasks = new HashSet<>();
        // Outer loop over all snapshot entries in the order they were created in
        for (SnapshotsInProgress.Entry entry : currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY).entries()) {
            if (entry.state().completed()) {
                // completed snapshots do not require any updates so we just add them to the new list and keep going
                entries.add(entry);
                continue;
            }
            ImmutableOpenMap.Builder<ShardId, ShardSnapshotStatus> shards = null;
            ImmutableOpenMap.Builder<RepositoryShardId, ShardSnapshotStatus> clones = null;
            Map<String, IndexId> indicesLookup = null;
            // inner loop over all the shard updates that are potentially applicable to the current snapshot entry
            for (Iterator<ShardSnapshotUpdate> iterator = unconsumedTasks.iterator(); iterator.hasNext();) {
                final ShardSnapshotUpdate updateSnapshotState = iterator.next();
                final Snapshot updatedSnapshot = updateSnapshotState.snapshot;
                final String updatedRepository = updatedSnapshot.getRepository();
                if (entry.repository().equals(updatedRepository) == false) {
                    // the update applies to a different repository so it is irrelevant here
                    continue;
                }
                if (updateSnapshotState.isClone()) {
                    // The update applied to a shard clone operation
                    final RepositoryShardId finishedShardId = updateSnapshotState.repoShardId;
                    if (entry.snapshot().getSnapshotId().equals(updatedSnapshot.getSnapshotId())) {
                        assert entry.isClone() : "Non-clone snapshot ["
                            + entry
                            + "] received update for clone ["
                            + updateSnapshotState
                            + "]";
                        final ShardSnapshotStatus existing = entry.clones().get(finishedShardId);
                        if (existing == null) {
                            logger.warn(
                                "Received clone shard snapshot status update [{}] but this shard is not tracked in [{}]",
                                updateSnapshotState,
                                entry
                            );
                            assert false
                                : "This should never happen, cluster-manager will not submit a state update for a non-existing clone";
                            continue;
                        }
                        if (existing.state().completed()) {
                            // No point in doing noop updates that might happen if data nodes resends shard status after a disconnect.
                            iterator.remove();
                            continue;
                        }
                        logger.trace(
                            "[{}] Updating shard clone [{}] with status [{}]",
                            updatedSnapshot,
                            finishedShardId,
                            updateSnapshotState.updatedState.state()
                        );
                        if (clones == null) {
                            clones = ImmutableOpenMap.builder(entry.clones());
                        }
                        changedCount++;
                        clones.put(finishedShardId, updateSnapshotState.updatedState);
                        executedTasks.add(updateSnapshotState);
                    } else if (executedTasks.contains(updateSnapshotState)) {
                        // the update was already executed on the clone operation it applied to, now we check if it may be possible to
                        // start a shard snapshot or clone operation on the current entry
                        if (entry.isClone()) {
                            // current entry is a clone operation
                            final ShardSnapshotStatus existingStatus = entry.clones().get(finishedShardId);
                            if (existingStatus == null || existingStatus.state() != ShardState.QUEUED) {
                                continue;
                            }
                            if (clones == null) {
                                clones = ImmutableOpenMap.builder(entry.clones());
                            }
                            final ShardSnapshotStatus finishedStatus = updateSnapshotState.updatedState;
                            logger.trace(
                                "Starting clone [{}] on [{}] with generation [{}]",
                                finishedShardId,
                                finishedStatus.nodeId(),
                                finishedStatus.generation()
                            );
                            assert finishedStatus.nodeId().equals(localNodeId) : "Clone updated with node id ["
                                + finishedStatus.nodeId()
                                + "] but local node id is ["
                                + localNodeId
                                + "]";
                            clones.put(finishedShardId, new ShardSnapshotStatus(finishedStatus.nodeId(), finishedStatus.generation()));
                            iterator.remove();
                        } else {
                            // current entry is a snapshot operation so we must translate the repository shard id to a routing shard id
                            final IndexMetadata indexMeta = currentState.metadata().index(finishedShardId.indexName());
                            if (indexMeta == null) {
                                // The index name that finished cloning does not exist in the cluster state so it isn't relevant to a
                                // normal snapshot
                                continue;
                            }
                            final ShardId finishedRoutingShardId = new ShardId(indexMeta.getIndex(), finishedShardId.shardId());
                            final ShardSnapshotStatus existingStatus = entry.shards().get(finishedRoutingShardId);
                            if (existingStatus == null || existingStatus.state() != ShardState.QUEUED) {
                                continue;
                            }
                            if (shards == null) {
                                shards = ImmutableOpenMap.builder(entry.shards());
                            }
                            final ShardSnapshotStatus finishedStatus = updateSnapshotState.updatedState;
                            logger.trace(
                                "Starting [{}] on [{}] with generation [{}]",
                                finishedShardId,
                                finishedStatus.nodeId(),
                                finishedStatus.generation()
                            );
                            // A clone was updated, so we must use the correct data node id for the reassignment as actual shard
                            // snapshot
                            final ShardSnapshotStatus shardSnapshotStatus = startShardSnapshotAfterClone(
                                currentState,
                                updateSnapshotState.updatedState.generation(),
                                finishedRoutingShardId
                            );
                            shards.put(finishedRoutingShardId, shardSnapshotStatus);
                            if (shardSnapshotStatus.isActive()) {
                                // only remove the update from the list of tasks that might hold a reusable shard if we actually
                                // started a snapshot and didn't just fail
                                iterator.remove();
                            }
                        }
                    }
                } else {
                    // a (non-clone) shard snapshot operation was updated
                    final ShardId finishedShardId = updateSnapshotState.shardId;
                    if (entry.snapshot().getSnapshotId().equals(updatedSnapshot.getSnapshotId())) {
                        final ShardSnapshotStatus existing = entry.shards().get(finishedShardId);
                        if (existing == null) {
                            logger.warn(
                                "Received shard snapshot status update [{}] but this shard is not tracked in [{}]",
                                updateSnapshotState,
                                entry
                            );
                            assert false : "This should never happen, data nodes should only send updates for expected shards";
                            continue;
                        }
                        if (existing.state().completed()) {
                            // No point in doing noop updates that might happen if data nodes resends shard status after a disconnect.
                            iterator.remove();
                            continue;
                        }
                        logger.trace(
                            "[{}] Updating shard [{}] with status [{}]",
                            updatedSnapshot,
                            finishedShardId,
                            updateSnapshotState.updatedState.state()
                        );
                        if (shards == null) {
                            shards = ImmutableOpenMap.builder(entry.shards());
                        }
                        shards.put(finishedShardId, updateSnapshotState.updatedState);
                        executedTasks.add(updateSnapshotState);
                        changedCount++;
                    } else if (executedTasks.contains(updateSnapshotState)) {
                        // We applied the update for a shard snapshot state to its snapshot entry, now check if we can update
                        // either a clone or a snapshot
                        if (entry.isClone()) {
                            // Since we updated a normal snapshot we need to translate its shard ids to repository shard ids which requires
                            // a lookup for the index ids
                            if (indicesLookup == null) {
                                indicesLookup = entry.indices().stream().collect(Collectors.toMap(IndexId::getName, Function.identity()));
                            }
                            // shard snapshot was completed, we check if we can start a clone operation for the same repo shard
                            final IndexId indexId = indicesLookup.get(finishedShardId.getIndexName());
                            // If the lookup finds the index id then at least the entry is concerned with the index id just updated
                            // so we check on a shard level
                            if (indexId != null) {
                                final RepositoryShardId repoShardId = new RepositoryShardId(indexId, finishedShardId.getId());
                                final ShardSnapshotStatus existingStatus = entry.clones().get(repoShardId);
                                if (existingStatus == null || existingStatus.state() != ShardState.QUEUED) {
                                    continue;
                                }
                                if (clones == null) {
                                    clones = ImmutableOpenMap.builder(entry.clones());
                                }
                                final ShardSnapshotStatus finishedStatus = updateSnapshotState.updatedState;
                                logger.trace(
                                    "Starting clone [{}] on [{}] with generation [{}]",
                                    finishedShardId,
                                    finishedStatus.nodeId(),
                                    finishedStatus.generation()
                                );
                                clones.put(repoShardId, new ShardSnapshotStatus(localNodeId, finishedStatus.generation()));
                                iterator.remove();
                                startedCount++;
                            }
                        } else {
                            // shard snapshot was completed, we check if we can start another snapshot
                            final ShardSnapshotStatus existingStatus = entry.shards().get(finishedShardId);
                            if (existingStatus == null || existingStatus.state() != ShardState.QUEUED) {
                                continue;
                            }
                            if (shards == null) {
                                shards = ImmutableOpenMap.builder(entry.shards());
                            }
                            final ShardSnapshotStatus finishedStatus = updateSnapshotState.updatedState;
                            logger.trace(
                                "Starting [{}] on [{}] with generation [{}]",
                                finishedShardId,
                                finishedStatus.nodeId(),
                                finishedStatus.generation()
                            );
                            shards.put(finishedShardId, new ShardSnapshotStatus(finishedStatus.nodeId(), finishedStatus.generation()));
                            iterator.remove();
                        }
                    }
                }
            }

            final SnapshotsInProgress.Entry updatedEntry;
            if (shards != null) {
                assert clones == null : "Should not have updated clones when updating shard snapshots but saw "
                    + clones
                    + " as well as "
                    + shards;
                updatedEntry = entry.withShardStates(shards.build());
            } else if (clones != null) {
                updatedEntry = entry.withClones(clones.build());
            } else {
                updatedEntry = entry;
            }
            entries.add(updatedEntry);
        }
        if (changedCount > 0) {
            logger.trace(
                "changed cluster state triggered by [{}] snapshot state updates and resulted in starting " + "[{}] shard snapshots",
                changedCount,
                startedCount
            );
            return ClusterStateTaskExecutor.ClusterTasksResult.<ShardSnapshotUpdate>builder()
                .successes(tasks)
                .build(ClusterState.builder(currentState).putCustom(SnapshotsInProgress.TYPE, SnapshotsInProgress.of(entries)).build());
        }
        return ClusterStateTaskExecutor.ClusterTasksResult.<ShardSnapshotUpdate>builder().successes(tasks).build(currentState);
    };

    /**
     * Creates a {@link ShardSnapshotStatus} entry for a snapshot after the shard has become available for snapshotting as a result
     * of a snapshot clone completing.
     *
     * @param currentState            current cluster state
     * @param shardGeneration         shard generation of the shard in the repository
     * @param shardId shard id of the shard that just finished cloning
     * @return shard snapshot status
     */
    private static ShardSnapshotStatus startShardSnapshotAfterClone(ClusterState currentState, String shardGeneration, ShardId shardId) {
        final ShardRouting primary = currentState.routingTable().index(shardId.getIndex()).shard(shardId.id()).primaryShard();
        final ShardSnapshotStatus shardSnapshotStatus;
        if (primary == null || !primary.assignedToNode()) {
            shardSnapshotStatus = new ShardSnapshotStatus(null, ShardState.MISSING, "primary shard is not allocated", shardGeneration);
        } else if (primary.relocating() || primary.initializing()) {
            shardSnapshotStatus = new ShardSnapshotStatus(primary.currentNodeId(), ShardState.WAITING, shardGeneration);
        } else if (primary.started() == false) {
            shardSnapshotStatus = new ShardSnapshotStatus(
                primary.currentNodeId(),
                ShardState.MISSING,
                "primary shard hasn't been started yet",
                shardGeneration
            );
        } else {
            shardSnapshotStatus = new ShardSnapshotStatus(primary.currentNodeId(), shardGeneration);
        }
        return shardSnapshotStatus;
    }

    /**
     * An update to the snapshot state of a shard.
     *
     * Package private for testing
     */
    static final class ShardSnapshotUpdate {

        private final Snapshot snapshot;

        private final ShardId shardId;

        private final RepositoryShardId repoShardId;

        private final ShardSnapshotStatus updatedState;

        ShardSnapshotUpdate(Snapshot snapshot, RepositoryShardId repositoryShardId, ShardSnapshotStatus updatedState) {
            this.snapshot = snapshot;
            this.shardId = null;
            this.updatedState = updatedState;
            this.repoShardId = repositoryShardId;
        }

        ShardSnapshotUpdate(Snapshot snapshot, ShardId shardId, ShardSnapshotStatus updatedState) {
            this.snapshot = snapshot;
            this.shardId = shardId;
            this.updatedState = updatedState;
            repoShardId = null;
        }

        public boolean isClone() {
            return repoShardId != null;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if ((other instanceof ShardSnapshotUpdate) == false) {
                return false;
            }
            final ShardSnapshotUpdate that = (ShardSnapshotUpdate) other;
            return this.snapshot.equals(that.snapshot)
                && Objects.equals(this.shardId, that.shardId)
                && Objects.equals(this.repoShardId, that.repoShardId)
                && this.updatedState == that.updatedState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshot, shardId, updatedState, repoShardId);
        }
    }

    /**
     * Updates the shard status in the cluster state
     *
     * @param update shard snapshot status update
     */
    private void innerUpdateSnapshotState(ShardSnapshotUpdate update, ActionListener<Void> listener) {
        logger.trace("received updated snapshot restore state [{}]", update);
        clusterService.submitStateUpdateTask(
            "update snapshot state",
            update,
            ClusterStateTaskConfig.build(Priority.NORMAL),
            SHARD_STATE_EXECUTOR,
            new ClusterStateTaskListener() {
                @Override
                public void onFailure(String source, Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    try {
                        listener.onResponse(null);
                    } finally {
                        // Maybe this state update completed the snapshot. If we are not already ending it because of a concurrent
                        // state update we check if its state is completed and end it if it is.
                        final SnapshotsInProgress snapshotsInProgress = newState.custom(
                            SnapshotsInProgress.TYPE,
                            SnapshotsInProgress.EMPTY
                        );
                        if (endingSnapshots.contains(update.snapshot) == false) {
                            final SnapshotsInProgress.Entry updatedEntry = snapshotsInProgress.snapshot(update.snapshot);
                            // If the entry is still in the cluster state and is completed, try finalizing the snapshot in the repo
                            if (updatedEntry != null && updatedEntry.state().completed()) {
                                endSnapshot(updatedEntry, newState.metadata(), null);
                            }
                        }
                        startExecutableClones(snapshotsInProgress, update.snapshot.getRepository());
                    }
                }
            }
        );
    }

    private void startExecutableClones(SnapshotsInProgress snapshotsInProgress, @Nullable String repoName) {
        for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
            if (entry.isClone() && entry.state() == State.STARTED && (repoName == null || entry.repository().equals(repoName))) {
                // this is a clone, see if new work is ready
                for (ObjectObjectCursor<RepositoryShardId, ShardSnapshotStatus> clone : entry.clones()) {
                    if (clone.value.state() == ShardState.INIT) {
                        runReadyClone(
                            entry.snapshot(),
                            entry.source(),
                            clone.value,
                            clone.key,
                            repositoriesService.repository(entry.repository())
                        );
                    }
                }
            }
        }
    }

    private class UpdateSnapshotStatusAction extends TransportClusterManagerNodeAction<
        UpdateIndexShardSnapshotStatusRequest,
        UpdateIndexShardSnapshotStatusResponse> {
        UpdateSnapshotStatusAction(
            TransportService transportService,
            ClusterService clusterService,
            ThreadPool threadPool,
            ActionFilters actionFilters,
            IndexNameExpressionResolver indexNameExpressionResolver
        ) {
            super(
                UPDATE_SNAPSHOT_STATUS_ACTION_NAME,
                false,
                transportService,
                clusterService,
                threadPool,
                actionFilters,
                UpdateIndexShardSnapshotStatusRequest::new,
                indexNameExpressionResolver
            );
        }

        @Override
        protected String executor() {
            return ThreadPool.Names.SAME;
        }

        @Override
        protected UpdateIndexShardSnapshotStatusResponse read(StreamInput in) throws IOException {
            return UpdateIndexShardSnapshotStatusResponse.INSTANCE;
        }

        @Override
        protected void clusterManagerOperation(
            UpdateIndexShardSnapshotStatusRequest request,
            ClusterState state,
            ActionListener<UpdateIndexShardSnapshotStatusResponse> listener
        ) throws Exception {
            innerUpdateSnapshotState(
                new ShardSnapshotUpdate(request.snapshot(), request.shardId(), request.status()),
                ActionListener.delegateFailure(listener, (l, v) -> l.onResponse(UpdateIndexShardSnapshotStatusResponse.INSTANCE))
            );
        }

        @Override
        protected ClusterBlockException checkBlock(UpdateIndexShardSnapshotStatusRequest request, ClusterState state) {
            return null;
        }
    }

    /**
     * Cluster state update task that removes all {@link SnapshotsInProgress.Entry} and {@link SnapshotDeletionsInProgress.Entry} for a
     * given repository from the cluster state and afterwards fails all relevant listeners in {@link #snapshotCompletionListeners} and
     * {@link #snapshotDeletionListeners}.
     */
    private final class FailPendingRepoTasksTask extends ClusterStateUpdateTask {

        // Snapshots to fail after the state update
        private final List<Snapshot> snapshotsToFail = new ArrayList<>();

        // Delete uuids to fail because after the state update
        private final List<String> deletionsToFail = new ArrayList<>();

        // Failure that caused the decision to fail all snapshots and deletes for a repo
        private final Exception failure;

        private final String repository;

        FailPendingRepoTasksTask(String repository, Exception failure) {
            this.repository = repository;
            this.failure = failure;
        }

        @Override
        public ClusterState execute(ClusterState currentState) {
            final SnapshotDeletionsInProgress deletionsInProgress = currentState.custom(
                SnapshotDeletionsInProgress.TYPE,
                SnapshotDeletionsInProgress.EMPTY
            );
            boolean changed = false;
            final List<SnapshotDeletionsInProgress.Entry> remainingEntries = deletionsInProgress.getEntries();
            List<SnapshotDeletionsInProgress.Entry> updatedEntries = new ArrayList<>(remainingEntries.size());
            for (SnapshotDeletionsInProgress.Entry entry : remainingEntries) {
                if (entry.repository().equals(repository)) {
                    changed = true;
                    deletionsToFail.add(entry.uuid());
                } else {
                    updatedEntries.add(entry);
                }
            }
            final SnapshotDeletionsInProgress updatedDeletions = changed ? SnapshotDeletionsInProgress.of(updatedEntries) : null;
            final SnapshotsInProgress snapshotsInProgress = currentState.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY);
            final List<SnapshotsInProgress.Entry> snapshotEntries = new ArrayList<>();
            boolean changedSnapshots = false;
            for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
                if (entry.repository().equals(repository)) {
                    // We failed to read repository data for this delete, it is not the job of SnapshotsService to
                    // retry these kinds of issues so we fail all the pending snapshots
                    snapshotsToFail.add(entry.snapshot());
                    changedSnapshots = true;
                } else {
                    // Entry is for another repository we just keep it as is
                    snapshotEntries.add(entry);
                }
            }
            final SnapshotsInProgress updatedSnapshotsInProgress = changedSnapshots ? SnapshotsInProgress.of(snapshotEntries) : null;
            return updateWithSnapshots(currentState, updatedSnapshotsInProgress, updatedDeletions);
        }

        @Override
        public void onFailure(String source, Exception e) {
            logger.info(
                () -> new ParameterizedMessage("Failed to remove all snapshot tasks for repo [{}] from cluster state", repository),
                e
            );
            failAllListenersOnMasterFailOver(e);
        }

        @Override
        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
            logger.warn(
                () -> new ParameterizedMessage(
                    "Removed all snapshot tasks for repository [{}] from cluster state, now failing listeners",
                    repository
                ),
                failure
            );
            synchronized (currentlyFinalizing) {
                Tuple<SnapshotsInProgress.Entry, Metadata> finalization;
                while ((finalization = repositoryOperations.pollFinalization(repository)) != null) {
                    assert snapshotsToFail.contains(finalization.v1().snapshot()) : "["
                        + finalization.v1()
                        + "] not found in snapshots to fail "
                        + snapshotsToFail;
                }
                leaveRepoLoop(repository);
                for (Snapshot snapshot : snapshotsToFail) {
                    failSnapshotCompletionListeners(snapshot, failure);
                }
                for (String delete : deletionsToFail) {
                    failListenersIgnoringException(snapshotDeletionListeners.remove(delete), failure);
                    repositoryOperations.finishDeletion(delete);
                }
            }
        }
    }

    private static final class OngoingRepositoryOperations {

        /**
         * Map of repository name to a deque of {@link SnapshotsInProgress.Entry} that need to be finalized for the repository and the
         * {@link Metadata to use when finalizing}.
         */
        private final Map<String, Deque<SnapshotsInProgress.Entry>> snapshotsToFinalize = new HashMap<>();

        /**
         * Set of delete operations currently being executed against the repository. The values in this set are the delete UUIDs returned
         * by {@link SnapshotDeletionsInProgress.Entry#uuid()}.
         */
        private final Set<String> runningDeletions = Collections.synchronizedSet(new HashSet<>());

        @Nullable
        private Metadata latestKnownMetaData;

        @Nullable
        synchronized Tuple<SnapshotsInProgress.Entry, Metadata> pollFinalization(String repository) {
            assertConsistent();
            final SnapshotsInProgress.Entry nextEntry;
            final Deque<SnapshotsInProgress.Entry> queued = snapshotsToFinalize.get(repository);
            if (queued == null) {
                return null;
            }
            nextEntry = queued.pollFirst();
            assert nextEntry != null;
            final Tuple<SnapshotsInProgress.Entry, Metadata> res = Tuple.tuple(nextEntry, latestKnownMetaData);
            if (queued.isEmpty()) {
                snapshotsToFinalize.remove(repository);
            }
            if (snapshotsToFinalize.isEmpty()) {
                latestKnownMetaData = null;
            }
            assert assertConsistent();
            return res;
        }

        boolean startDeletion(String deleteUUID) {
            return runningDeletions.add(deleteUUID);
        }

        void finishDeletion(String deleteUUID) {
            runningDeletions.remove(deleteUUID);
        }

        synchronized void addFinalization(SnapshotsInProgress.Entry entry, Metadata metadata) {
            snapshotsToFinalize.computeIfAbsent(entry.repository(), k -> new LinkedList<>()).add(entry);
            this.latestKnownMetaData = metadata;
            assertConsistent();
        }

        /**
         * Clear all state associated with running snapshots. To be used on cluster-manager-failover if the current node stops
         * being cluster-manager.
         */
        synchronized void clear() {
            snapshotsToFinalize.clear();
            runningDeletions.clear();
            latestKnownMetaData = null;
        }

        synchronized boolean isEmpty() {
            return snapshotsToFinalize.isEmpty();
        }

        synchronized boolean assertNotQueued(Snapshot snapshot) {
            assert snapshotsToFinalize.getOrDefault(snapshot.getRepository(), new LinkedList<>())
                .stream()
                .noneMatch(entry -> entry.snapshot().equals(snapshot)) : "Snapshot [" + snapshot + "] is still in finalization queue";
            return true;
        }

        synchronized boolean assertConsistent() {
            assert (latestKnownMetaData == null && snapshotsToFinalize.isEmpty())
                || (latestKnownMetaData != null && snapshotsToFinalize.isEmpty() == false)
                : "Should not hold on to metadata if there are no more queued snapshots";
            assert snapshotsToFinalize.values().stream().noneMatch(Collection::isEmpty) : "Found empty queue in " + snapshotsToFinalize;
            return true;
        }
    }
}
