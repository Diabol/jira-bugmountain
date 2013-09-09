package com.ongame.jira;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.jql.builder.JqlQueryBuilder;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.query.Query;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.block.BlockBorder;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.Week;
import org.jfree.data.xy.TableXYDataset;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;
import static org.apache.commons.lang.StringEscapeUtils.unescapeHtml;
import static org.jfree.chart.ChartFactory.createStackedXYAreaChart;
import static org.jfree.chart.plot.PlotOrientation.VERTICAL;

/**
 * Main server side class for providing services to the gadget. Currently generates a chart image and provides
 * filter/project name. Uses REST for the communication.
 *
 * Note: Requires "Accept remote API calls" to be enabled in the JIRA container.
 */
@Path("/")
public class BugMountain
{
    private final JiraAuthenticationContext authenticationContext;
    private final SearchProvider searchProvider;
    private final SearchRequestService searchRequestService;
    private final ProjectManager projectManager;
    private final SearchService searchService;

    private final static Logger logger = Logger.getLogger(BugMountain.class);

    public BugMountain(final JiraAuthenticationContext authenticationContext,
                       final SearchProvider searchProvider,
                       final SearchRequestService searchRequestService,
                       final ProjectManager projectManager,
                       final SearchService searchService)
    {
        this.authenticationContext = authenticationContext;
        this.searchProvider = searchProvider;
        this.searchRequestService = searchRequestService;
        this.projectManager = projectManager;
        this.searchService = searchService;
    }

    /**
     * @param filterId The filter key
     * @return Retuns a JSON-encoded name for the given filter, or an error status for non-visible/non-existing filters.
     */
    @GET
    @Produces ({ MediaType.APPLICATION_JSON })
    @Path("/filter-{filterId}/name")
    public Response getNameForFilter(@PathParam("filterId") long filterId) {
        User user = authenticationContext.getLoggedInUser();
        SearchRequest searchRequest = searchRequestService.getFilter(new JiraServiceContextImpl(user), filterId);
        if(searchRequest == null) {
            logger.warn("No such filter : " + filterId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        else {
            return Response.ok("{\"name\":\"" + escapeHtml(searchRequest.getName()) + "\"}").build();
        }
    }

    /**
     * @param projectId The project key
     * @return Retuns a JSON-encoded name for the given project,
     *         or an error status for non-visible/non-existing projects.
     */
    @GET
    @Produces ({ MediaType.APPLICATION_JSON })
    @Path("/project-{projectId}/name")
    public Response getNameForProject(@PathParam("projectId") long projectId) {
        Project project = projectManager.getProjectObj(projectId);
        if (project == null) {
            logger.warn("No such project : " + projectId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        else {
            return Response.ok("{\"name\":\"" + escapeHtml(project.getName()) + "\"}").build();
        }
    }

    /**
     * @param jql The jql statement (will be ignored)
     * @return The string "Ad hoc JQL"
     */
    @GET
    @Produces ({ MediaType.APPLICATION_JSON })
    @Path("/jql-{jql}/name")
    public Response getNameForJQL(@PathParam("jql") String jql) {
        return Response.ok("{\"name\":\"Ad hoc JQL\"}").build();
    }

    /**
     * @param width The width for the chart.
     * @param height The height for the chart.
     * @param filterId The filterId that specifies 
     * @return A png-encoded image of last years "bug mountain" chart.
     */
    @GET
    @Produces("image/png")
    @Path("/filter-{filterId}/chart-{width}x{height}")
    public Response getChartFromFilter(@PathParam("width") int width,
                                       @PathParam("height") int height,
                                       @PathParam("filterId") long filterId)
    {
        User user = authenticationContext.getLoggedInUser();
        SearchRequest searchRequest = searchRequestService.getFilter(new JiraServiceContextImpl(user), filterId);
        if(searchRequest == null) {
            logger.warn("No such filter : " + filterId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        else {
            return getChart(width, height, searchRequest.getQuery());
        }
    }

    @GET
    @Produces("image/png")
    @Path("/project-{projectId}/chart-{width}x{height}")
    public Response getChartFromProject(@PathParam("width") int width,
                                        @PathParam("height") int height,
                                        @PathParam("projectId") long projectId)
    {
        Project project = projectManager.getProjectObj(projectId);
        if (project == null) {
            logger.warn("No such project : " + projectId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        else {
            return getChart(width, height, JqlQueryBuilder.newBuilder().where().project(project.getKey()).buildQuery());
        }
    }

    @GET
    @Produces("image/png")
    @Path("/jql-{jql}/chart-{width}x{height}")
    public Response getChartFromJQL(@PathParam("width") int width,
                                    @PathParam("height") int height,
                                    @PathParam("jql") String jql)
    {
        User user = authenticationContext.getLoggedInUser();
        SearchService.ParseResult parseResult = searchService.parseQuery(user, unescapeHtml(jql));
        if (parseResult.isValid()) {
            return getChart(width, height, parseResult.getQuery());
        }
        else {
            logger.warn("Invalid JQL : " + jql);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    }


    public Response getChart(int width, int height, Query query)
    {
        try {
            Week currentWeek = new Week();
            Calendar calendar = new GregorianCalendar();
            calendar.add(Calendar.YEAR, -1);
            Week firstWeek = new Week(calendar.getTime());

            logger.debug("Searching for issues...");
            User user = authenticationContext.getUser();
            // todo: Process issues in chunks (of 100 or so) to save memory footprint
            SearchResults searchResults = searchProvider.search(query, user, PagerFilter.getUnlimitedFilter());
            IssueData issueData = new IssueData(firstWeek, currentWeek);
            for(Issue issue : searchResults.getIssues())
                issueData.add(issue);

            logger.debug("Generating chart...");
            JFreeChart chart = createChart(issueData);
            return Response.ok(chart.createBufferedImage(width, height, BufferedImage.TYPE_INT_RGB, null)).build();
        }
        catch (SearchException e) {
            logger.warn(e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        catch (Throwable e) {
            logger.log(Level.WARN, "Caught throwable.", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }


    private JFreeChart createChart(IssueData issueData)
    {
        // Create "vanilla" chart
        TableXYDataset dataset = issueData.asTableXYDataset();
        JFreeChart chart = createStackedXYAreaChart(null, null, null, dataset, VERTICAL, true, false, false);
        chart.setBackgroundPaint(Color.WHITE);
        
        // Make x-axis display dates
        DateAxis axis = new DateAxis();
        axis.setLowerMargin(0);
        axis.setUpperMargin(0);
        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setDomainAxis(axis);

        // Remove border from legend
        chart.getLegend().setFrame(BlockBorder.NONE);

        // Init colors for series, use the color property from Jiras priority object
        List<Color> colorsForSeries = issueData.getColorsForSeries();
        for(int i = 0; i < colorsForSeries.size(); i++)
            plot.getRenderer().setSeriesPaint(i, colorsForSeries.get(i));

        return chart;
    }
}
