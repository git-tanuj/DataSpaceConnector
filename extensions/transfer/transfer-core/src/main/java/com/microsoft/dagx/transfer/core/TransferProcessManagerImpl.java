package com.microsoft.dagx.transfer.core;

import com.microsoft.dagx.spi.message.RemoteMessageDispatcherRegistry;
import com.microsoft.dagx.spi.monitor.Monitor;
import com.microsoft.dagx.spi.transfer.TransferInitiateResponse;
import com.microsoft.dagx.spi.transfer.TransferProcessManager;
import com.microsoft.dagx.spi.transfer.TransferWaitStrategy;
import com.microsoft.dagx.spi.transfer.flow.DataFlowManager;
import com.microsoft.dagx.spi.transfer.provision.ProvisionManager;
import com.microsoft.dagx.spi.transfer.provision.ResourceManifestGenerator;
import com.microsoft.dagx.spi.transfer.response.ResponseStatus;
import com.microsoft.dagx.spi.transfer.store.TransferProcessStore;
import com.microsoft.dagx.spi.types.domain.transfer.DataRequest;
import com.microsoft.dagx.spi.types.domain.transfer.ResourceManifest;
import com.microsoft.dagx.spi.types.domain.transfer.TransferProcess;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.microsoft.dagx.spi.types.domain.transfer.TransferProcess.Type.CLIENT;
import static com.microsoft.dagx.spi.types.domain.transfer.TransferProcess.Type.PROVIDER;
import static com.microsoft.dagx.spi.types.domain.transfer.TransferProcessStates.INITIAL;
import static com.microsoft.dagx.spi.types.domain.transfer.TransferProcessStates.PROVISIONED;
import static java.util.UUID.randomUUID;

/**
 *
 */
public class TransferProcessManagerImpl implements TransferProcessManager {
    private int batchSize = 5;
    private TransferWaitStrategy waitStrategy = () -> 5000L;  // default wait five seconds

    private ResourceManifestGenerator manifestGenerator;
    private ProvisionManager provisionManager;
    private TransferProcessStore transferProcessStore;
    private RemoteMessageDispatcherRegistry dispatcherRegistry;
    private DataFlowManager dataFlowManager;

    private Monitor monitor;

    private ExecutorService executor;

    private AtomicBoolean active = new AtomicBoolean();

    public void start(TransferProcessStore processStore) {
        this.transferProcessStore = processStore;
        active.set(true);
        executor = Executors.newSingleThreadExecutor();
        executor.submit(this::run);
    }

    public void stop() {
        active.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public TransferInitiateResponse initiateClientRequest(DataRequest dataRequest) {
        return initiateRequest(dataRequest, CLIENT);
    }

    @Override
    public TransferInitiateResponse initiateProviderRequest(DataRequest dataRequest) {
        return initiateRequest(dataRequest, PROVIDER);
    }

    private TransferInitiateResponse initiateRequest(DataRequest dataRequest, TransferProcess.Type type) {
        TransferProcess process = TransferProcess.Builder.newInstance().id(randomUUID().toString()).dataRequest(dataRequest).type(type).build();
        transferProcessStore.create(process);
        return TransferInitiateResponse.Builder.newInstance().id(process.getId()).status(ResponseStatus.OK).build();
    }

    private void run() {
        try {
            while (active.get()) {
                int provisioned = provisionInitialProcesses();

                // TODO check processes in provisioning state and timestamps for failed processes

                int sent = sendOrProcessProvisionedRequests();

                if (provisioned == 0 && sent == 0) {
                    //noinspection BusyWait
                    Thread.sleep(waitStrategy.waitForMillis());
                }
            }
        } catch (Error e) {
            throw e; // let the thread die and don't reschedule as the error is unrecoverable
        } catch (Throwable e) {
            monitor.severe("Error caught in transfer process manager", e);
        }
    }

    /**
     * Performs client-side or provider side provisioning for a service.
     *
     * On a client, provisioning may entail setting up a data destination and supporting infrastructure. On a provider, provisioning is initiated when a request is received and
     * map involve preprocessing data or other operations.
     */
    private int provisionInitialProcesses() {
        List<TransferProcess> processes = transferProcessStore.nextForState(INITIAL.code(), batchSize);
        for (TransferProcess process : processes) {
            DataRequest dataRequest = process.getDataRequest();
            ResourceManifest manifest = process.getType() == CLIENT ? manifestGenerator.generateClientManifest(dataRequest) : manifestGenerator.generateProviderManifest(dataRequest);
            process.transitionProvisioning(manifest);
            transferProcessStore.update(process);
            provisionManager.provision(process);
        }
        return processes.size();
    }

    /**
     * On a client, sends provisioned requests to the provider connector. On the provider, sends provisioned requests to the data flow manager.
     *
     * @return the number of requests processed
     */
    private int sendOrProcessProvisionedRequests() {
        List<TransferProcess> processes = transferProcessStore.nextForState(PROVISIONED.code(), batchSize);
        for (TransferProcess process : processes) {
            DataRequest dataRequest = process.getDataRequest();
            if (CLIENT == process.getType()) {
                dispatcherRegistry.send(Void.class, dataRequest).whenComplete((response, exception) -> {
                    if (exception != null) {
                        monitor.severe("Error sending request process id: " + process.getId(), exception);
                        process.transitionError(exception.getMessage());
                    } else {
                        process.transitionRequestAck();
                    }
                    transferProcessStore.update(process);
                });
                process.transitionRequested();
            } else {
                dataFlowManager.initiate(dataRequest);
                process.transitionInProgress();
            }
            transferProcessStore.update(process);
        }
        return processes.size();
    }

    private TransferProcessManagerImpl() {
    }

    public static class Builder {
        private TransferProcessManagerImpl manager;

        public static Builder newInstance() {
            return new Builder();
        }

        public Builder batchSize(int size) {
            manager.batchSize = size;
            return this;
        }

        public Builder waitStrategy(TransferWaitStrategy waitStrategy) {
            manager.waitStrategy = waitStrategy;
            return this;
        }

        public Builder manifestGenerator(ResourceManifestGenerator manifestGenerator) {
            manager.manifestGenerator = manifestGenerator;
            return this;
        }

        public Builder provisionManager(ProvisionManager provisionManager) {
            manager.provisionManager = provisionManager;
            return this;
        }

        public Builder dataFlowManager(DataFlowManager dataFlowManager) {
            manager.dataFlowManager = dataFlowManager;
            return this;
        }

        public Builder dispatcherRegistry(RemoteMessageDispatcherRegistry registry) {
            manager.dispatcherRegistry = registry;
            return this;
        }

        public Builder monitor(Monitor monitor) {
            manager.monitor = monitor;
            return this;
        }

        public TransferProcessManagerImpl build() {
            Objects.requireNonNull(manager.manifestGenerator,"manifestGenerator");
            Objects.requireNonNull(manager.provisionManager,"provisionManager");
            Objects.requireNonNull(manager.dataFlowManager,"dataFlowManager");
            Objects.requireNonNull(manager.dispatcherRegistry,"dispatcherRegistry");
            Objects.requireNonNull(manager.monitor,"monitor");
            return manager;
        }

        private Builder() {
            manager = new TransferProcessManagerImpl();
        }
    }
}
