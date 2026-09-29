package distrilab.worker;

import distrilab.api.InvalidJobException;
import distrilab.api.JobExecutionException;
import distrilab.api.NotCoordinatorException;
import distrilab.api.WorkerService;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.CoordinatorMessage;
import distrilab.api.model.ElectionMessage;
import distrilab.api.model.ElectionReply;
import distrilab.api.model.JobResult;
import distrilab.api.model.TaskResult;
import distrilab.api.model.WorkerRef;
import distrilab.api.model.WorkerStatus;
import distrilab.jobs.Job;
import distrilab.jobs.JobLimits;
import distrilab.util.Log;

import java.util.ArrayList;

/**
 * The remote face of a worker. It holds no logic of its own: each remote method
 * delegates to the component responsible (overlay, election, coordinator role, task
 * executor). RMI may call these methods from many threads at once; thread safety is
 * provided inside the components.
 */
final class WorkerServiceImpl implements WorkerService {

    private final NodeIdentity self;
    private final NeighbourTable neighbours;
    private final OverlayManager overlay;
    private final ElectionManager election;
    private final JobCoordinator coordinator;
    private final TaskExecutor executor;
    private final JobLimits limits;

    WorkerServiceImpl(NodeIdentity self, NeighbourTable neighbours, OverlayManager overlay,
                      ElectionManager election, JobCoordinator coordinator, TaskExecutor executor, JobLimits limits) {
        this.self = self;
        this.neighbours = neighbours;
        this.overlay = overlay;
        this.election = election;
        this.coordinator = coordinator;
        this.executor = executor;
        this.limits = limits;
    }

    // ---------------------------------------------------------------- client-facing

    @Override
    public JobResult submitJob(Job job, String clientId)
            throws NotCoordinatorException, InvalidJobException, JobExecutionException {
        if (job == null) {
            throw new InvalidJobException("No job was supplied");
        }
        job.validate(limits); // invalid jobs are rejected before they use up a slot in the term
        JobCoordinator.Slot slot = coordinator.openSlot(election.currentView());
        if (slot.closesTerm()) {
            Log.info("Term %d is complete (%d of %d jobs assigned) - starting the next election",
                    slot.getTerm(), slot.getJobNumber(), coordinator.getMaxJobsPerTerm());
            election.startElection("term " + slot.getTerm() + " of Worker " + self.id() + " is complete", false);
        }
        return coordinator.execute(job, clientId, slot);
    }

    @Override
    public CoordinatorInfo getCoordinatorInfo() {
        return election.currentView();
    }

    @Override
    public boolean isActiveCoordinator() {
        return coordinator.isActive();
    }

    @Override
    public void requestElection(String reason) {
        CoordinatorInfo view = election.currentView();
        if (coordinator.isActive() || election.electionInProgress()) {
            return;
        }
        if (view.hasCoordinator() && view.getCoordinatorId() != self.id()) {
            try {
                if (view.getCoordinatorRef().getStub().isActiveCoordinator()) {
                    return; // there is an active coordinator - no election needed
                }
            } catch (java.rmi.RemoteException e) {
                election.coordinatorFailed(view.getCoordinatorId());
            }
        }
        election.startElection("requested: " + reason, true);
    }

    // ---------------------------------------------------------------- worker-to-worker

    @Override
    public int getWorkerId() {
        return self.id();
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public boolean addNeighbour(WorkerRef neighbour) {
        return overlay.acceptLink(neighbour);
    }

    @Override
    public void removeNeighbour(int workerId) {
        overlay.neighbourLeft(workerId);
    }

    @Override
    public ElectionReply onElection(ElectionMessage message) {
        return election.onElection(message);
    }

    @Override
    public void onCoordinator(CoordinatorMessage message) {
        election.onCoordinator(message);
    }

    @Override
    public TaskResult executeTask(String taskId, Job task) throws JobExecutionException {
        if (task == null) {
            throw new JobExecutionException("No task was supplied");
        }
        return executor.execute(taskId, task);
    }

    @Override
    public WorkerStatus getStatus() {
        CoordinatorInfo view = election.currentView();
        return new WorkerStatus(self.id(), self.host(), coordinator.currentJac(), coordinator.isActive(),
                view.getTerm(), view.getCoordinatorId(), coordinator.getJobsAssignedInTerm(),
                coordinator.getMaxJobsPerTerm(), new ArrayList<>(neighbours.ids()), executor.getThreads(),
                executor.getRunning(), executor.getPeak(), executor.getCompleted(),
                election.getElectionsProcessed(), election.getDuplicatesIgnored());
    }
}
