package com.ongame.jira;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.priority.Priority;
import org.jfree.data.time.TimeTableXYDataset;
import org.jfree.data.time.Week;
import org.jfree.data.xy.TableXYDataset;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Represents issue data needed for the bug mountain chart. Will only collect data relevant for the time period
 * specifiec in the constructor.
 */
class IssueData
{
    private final Map<Priority, Map<Week, Integer>> prioToWeeklyMap = new TreeMap<Priority, Map<Week, Integer>>();
    private final Week min, max;

    IssueData(Week min, Week max)
    {
        this.min = min;
        this.max = max;
    }

    void add(Issue issue)
    {
        Priority prio = issue.getPriorityObject();
        Week from = new Week(issue.getCreated());
        Week to = issue.getResolutionDate() != null? new Week(issue.getResolutionDate()): null;

        // Some issues have no prio, ignore!
        if(prio == null)
            return;

        // Some issues are fixed before graph begins, ignore!
        if(to != null && to.compareTo(min) <= 0)
            return;

        // Always increment bugcount... and decrement if fixed
        increment(prio, max(from, min), 1);
        if(to != null)
            increment(prio, to, -1);
    }

    int get(Priority prio, Week week)
    {
        Map<Week, Integer> weeklyMap = prioToWeeklyMap.get(prio);
        if(weeklyMap == null)
            return 0;
        Integer value = weeklyMap.get(week);
        return value != null? value: 0;
    }

    private void increment(Priority prio, Week week, int increment)
    {
        Map<Week, Integer> weeklyMap = prioToWeeklyMap.get(prio);
        if(weeklyMap == null) {
            weeklyMap = new TreeMap<Week, Integer>();
            prioToWeeklyMap.put(prio, weeklyMap);
        }
        Integer oldValue = weeklyMap.get(week);
        weeklyMap.put(week, oldValue != null? oldValue + increment: increment);
    }

    /**
     * @return a list of the seen priorities, _in sequence order_ as specified by the priority objects.
     */
    private List<Priority> getPriorities()
    {
        List<Priority> priorities = new ArrayList<Priority>(prioToWeeklyMap.keySet());
        Collections.sort(priorities, new Comparator<Priority>() {
            public int compare(Priority first, Priority second)
            {
                return (int) (first.getSequence() - second.getSequence());
            }
        });
        return priorities;
    }

    List<Color> getColorsForSeries()
    {
        List<Color> colors = new ArrayList<Color>();
        for(Priority prio : getPriorities())
            colors.add(Color.decode(prio.getGenericValue().getString("statusColor")));
        return colors;
    }

    /**
     * Present data in a way that is directly usable by the StackedXYAreaChart.
     * @return A view of the data, never null.
     */
    TableXYDataset asTableXYDataset()
    {
        TimeTableXYDataset dataset = new TimeTableXYDataset();
        dataset.setDomainIsPointsInTime(true);
        for(Priority prio : getPriorities()) {
            int currentCount = 0;
            for(Week week = min; week.compareTo(max) <= 0; week = (Week) week.next()) {
                currentCount += get(prio, week);
                dataset.add(week, currentCount, prio.getName());
            }
        }
        return dataset;
    }

    public static <T extends Comparable<T>> T max(T first, T second)
    {
        return first.compareTo(second) >= 0? first: second;
    }
}
