package distrilab.client.gui;

import distrilab.api.model.JobResult;
import distrilab.jobs.Job;
import distrilab.util.Formats;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Table of submitted jobs. EDT-confined: every method must be called on the Swing thread. */
final class JobTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;

    private static final String[] COLUMNS = {"#", "Job", "Status", "Result", "Coordinator / term", "Workers", "ms"};

    private final List<JobRecord> rows = new ArrayList<>();

    JobRecord add(Job job) {
        JobRecord record = new JobRecord(rows.size() + 1, job);
        rows.add(record);
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        return record;
    }

    void changed(JobRecord record) {
        int row = record.getNumber() - 1;
        fireTableRowsUpdated(row, row);
    }

    JobRecord get(int row) {
        return rows.get(row);
    }

    long count(JobRecord.State state) {
        return rows.stream().filter(r -> r.getState() == state).count();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int column) {
        JobRecord r = rows.get(rowIndex);
        JobResult result = r.getResult();
        switch (column) {
            case 0:
                return r.getNumber();
            case 1:
                return Formats.abbreviate(r.getJob().describe(), 60);
            case 2:
                return r.getState() == JobRecord.State.RUNNING ? r.getProgress() : r.getState().name();
            case 3:
                if (result != null) {
                    return result.getValueText();
                }
                return r.getError() == null ? "" : r.getError();
            case 4:
                return result == null ? "" : String.format("Worker %d \u00b7 term %d \u00b7 job %d/%d",
                        result.getCoordinatorId(), result.getTerm(), result.getJobNumberInTerm(),
                        result.getMaxJobsPerTerm());
            case 5:
                return result == null ? "" : result.getWorkerIds().stream().map(String::valueOf)
                        .collect(Collectors.joining(", "));
            case 6:
                return r.getState() == JobRecord.State.QUEUED ? "" : String.valueOf(r.getElapsedMs());
            default:
                return "";
        }
    }
}
