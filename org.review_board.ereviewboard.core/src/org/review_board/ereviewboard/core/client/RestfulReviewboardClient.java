/*******************************************************************************
 * Copyright (c) 2004 - 2009 Mylyn project committers and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Mylyn project committers, Atlassian, Sven Krzyzak
 *******************************************************************************/
/*******************************************************************************
 * Copyright (c) 2009 Markus Knittig
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * Contributors:
 *     Markus Knittig - adapted Trac, Redmine & Atlassian implementations for
 *                      Review Board
 *******************************************************************************/
package org.review_board.ereviewboard.core.client;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.mylyn.commons.net.AbstractWebLocation;
import org.eclipse.mylyn.commons.net.Policy;
import org.eclipse.mylyn.tasks.core.IRepositoryPerson;
import org.eclipse.mylyn.tasks.core.IRepositoryQuery;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttachmentMapper;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskCommentMapper;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.core.data.TaskDataCollector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.review_board.ereviewboard.core.ReviewboardAttributeMapper;
import org.review_board.ereviewboard.core.ReviewboardAttributeMapper.Attribute;
import org.review_board.ereviewboard.core.ReviewboardCorePlugin;
import org.review_board.ereviewboard.core.ReviewboardTaskMapper;
import org.review_board.ereviewboard.core.exception.ReviewboardException;
import org.review_board.ereviewboard.core.model.Comment;
import org.review_board.ereviewboard.core.model.Diff;
import org.review_board.ereviewboard.core.model.Repository;
import org.review_board.ereviewboard.core.model.Review;
import org.review_board.ereviewboard.core.model.ReviewGroup;
import org.review_board.ereviewboard.core.model.ReviewRequest;
import org.review_board.ereviewboard.core.model.ReviewRequestStatus;
import org.review_board.ereviewboard.core.model.Screenshot;
import org.review_board.ereviewboard.core.model.ServerInfo;
import org.review_board.ereviewboard.core.model.User;
import org.review_board.ereviewboard.core.util.ReviewboardUtil;

/**
 * RESTful implementation of {@link ReviewboardClient}.
 * 
 * @author Markus Knittig
 */
public class RestfulReviewboardClient implements ReviewboardClient {
    
    private final AbstractWebLocation location;

    private final RestfulReviewboardReader reviewboardReader;

    private ReviewboardClientData clientData;

    private ReviewboardHttpClient httpClient;

    public RestfulReviewboardClient(AbstractWebLocation location, ReviewboardClientData clientData,
            TaskRepository repository) {
        this.location = location;
        this.clientData = clientData;

        reviewboardReader = new RestfulReviewboardReader();

        httpClient = new ReviewboardHttpClient(location, repository.getCharacterEncoding(),
                Boolean.valueOf(repository.getProperty("selfSignedSSL")));

        refreshRepositorySettings(repository);
    }

    public ReviewboardClientData getClientData() {
        return clientData;
    }

    public void refreshRepositorySettings(TaskRepository repository) {
        // Nothing to do yet
    }

    public TaskData getTaskData(TaskRepository taskRepository, final String taskId,
            IProgressMonitor monitor) throws ReviewboardException {

        long start = System.currentTimeMillis();

        try {
            
            TaskData taskData = new TaskData(new ReviewboardAttributeMapper(taskRepository),
                    ReviewboardCorePlugin.REPOSITORY_KIND, taskRepository.getUrl(), taskId);
            JSONObject jsonResult = new JSONObject(httpClient.executeGet(
                    "/api/review-requests/" + Integer.parseInt(taskId) + "/", monitor)).getJSONObject("review_request");

            // people attributes
            mapPeopleGroup(taskData, jsonResult, Attribute.TARGET_PEOPLE);
            mapPeopleGroup(taskData, jsonResult, Attribute.TARGET_GROUPS);
            
            mapJsonAttribute(jsonResult.getJSONObject("links"), taskData, ReviewboardAttributeMapper.Attribute.REPOSITORY);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.BRANCH);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.CHANGENUM);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.STATUS);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.PUBLIC);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.BUGS_CLOSED);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.TESTING_DONE);

            
            // hidden attributes
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.SUMMARY);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.ID);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.DESCRIPTION);
            mapJsonAttribute(jsonResult.getJSONObject("links"), taskData, ReviewboardAttributeMapper.Attribute.SUBMITTER);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.LAST_UPDATED);
            mapJsonAttribute(jsonResult, taskData, ReviewboardAttributeMapper.Attribute.TIME_ADDED);
            
            ReviewRequestStatus status =  ReviewRequestStatus.parseStatus( jsonResult.getString(ReviewboardAttributeMapper.Attribute.STATUS.getJsonAttributeName()));
            if ( status != ReviewRequestStatus.PENDING ) {
                TaskAttribute completion = taskData.getRoot().createMappedAttribute(TaskAttribute.DATE_COMPLETION);
                completion.getMetaData().setReadOnly(true);
                completion.getMetaData().setKind(null);
                completion.getMetaData().setType(TaskAttribute.TYPE_DATETIME);
                completion.setValue(extractPossiblyNestedJsonValue(jsonResult, ReviewboardAttributeMapper.Attribute.LAST_UPDATED));
            }
            
            // TODO: these should be joined to make sure we only use one call
            loadReviewsAndDiffsAsComment(taskData, monitor);
            loadDiffsAndScreenshotsAsAttachments(taskData, taskRepository, monitor);

            return taskData;
        } catch (JSONException e) {
            throw new ReviewboardException("Error marshalling object to JSON", e);
        } finally {
            
            double elapsed= ( System.currentTimeMillis() - start) / 1000.0;
            
            System.out.println("Review request with id  " + taskId + " synchronized in " + NumberFormat.getNumberInstance().format(elapsed) + " seconds.");
        }
    }

    public void mapPeopleGroup(TaskData taskData, JSONObject jsonResult, Attribute targetAttribute) throws JSONException {
        
        JSONArray jsonArray = jsonResult.getJSONArray(targetAttribute.getJsonAttributeName());
        
        List<String> reviewPersons = new ArrayList<String>();
        for ( int i = 0 ; i < jsonArray.length(); i++ ) {
            
            JSONObject object = jsonArray.getJSONObject(i);
            reviewPersons.add(object.getString("title"));
        }
        
        TaskAttribute taskAttribute = taskData.getRoot().createAttribute(targetAttribute.toString());
        taskAttribute.setValues(reviewPersons);
        
        taskAttribute.getMetaData().setReadOnly(true).setLabel(targetAttribute.getDisplayName()).setType(targetAttribute.getAttributeType()).setKind(TaskAttribute.KIND_PEOPLE);
    }

    private void mapJsonAttribute(JSONObject from, TaskData to, Attribute attribute)
            throws JSONException {

        TaskAttribute taskAttribute = to.getRoot().createAttribute(attribute.toString());
        taskAttribute.setValue(extractPossiblyNestedJsonValue(from, attribute));
        taskAttribute.getMetaData().setReadOnly(true);
        taskAttribute.getMetaData().setLabel(attribute.getDisplayName());
        taskAttribute.getMetaData().setType(attribute.getAttributeType());
        taskAttribute.getMetaData().setKind(
                attribute.isHidden() ? null : TaskAttribute.KIND_DEFAULT);
    }

    private String extractPossiblyNestedJsonValue(JSONObject from, Attribute attribute)
            throws JSONException {

        String jsonAttributeName = attribute.getJsonAttributeName();
        int separatorIndex = jsonAttributeName.indexOf('.');

        if (separatorIndex == -1)
            return splitWordListIfApplicable(ReviewboardUtil.unmaskNull(from.getString(jsonAttributeName)));

        String[] paths = jsonAttributeName.split("\\.");
        Assert.isTrue(paths.length == 2, "Expected paths length of 2, got " + paths.length + " .");
        
        return splitWordListIfApplicable(from.getJSONObject(paths[0]).getString(paths[1]));
    }
    
    private String splitWordListIfApplicable(String value) {

        List<String> values = new ArrayList<String>();
        
        if (value.startsWith("[") && value.endsWith("]")) {

            Pattern pattern = Pattern.compile("\"(\\w+)\"+");

            Matcher matcher = pattern.matcher(value.substring(1, value.length() - 1));

            while (matcher.find())
                values.add((matcher.group(1)));
        } else {
            values.add(value);
        }
        
        return ReviewboardUtil.joinList(values);
    }

    private void loadReviewsAndDiffsAsComment(TaskData taskData, IProgressMonitor monitor)
            throws JSONException, ReviewboardException {

        JSONObject diffs = new JSONObject(httpClient.executeGet("/api/json/reviewrequests/"
                + taskData.getTaskId() + "/diff", monitor));

        Policy.advance(monitor, 1);

        JSONArray diffArray = diffs.getJSONArray("diffsets");

        SortedMap<Date, Comment2> sortedComments = new TreeMap<Date, Comment2>();

        for (int i = 0; i < diffArray.length(); i++) {

            JSONObject jsonComment = diffArray.getJSONObject(i);

            Comment2 comment = new Comment2();
            comment.setAuthor(taskData.getAttributeMapper().getTaskRepository().createPerson(
                    taskData.getRoot().getAttribute(Attribute.SUBMITTER.toString()).getValue()));
            comment.setText(Diff.DIFF_REVISION_PREFIX + jsonComment.getString("revision"));

            sortedComments.put(
                    ReviewboardAttributeMapper.parseDateValue(jsonComment.getString("timestamp")),
                    comment);
        }

        JSONObject reviews = new JSONObject(httpClient.executeGet("/api/review-requests/" + taskData.getTaskId() + "/reviews", monitor));

        Policy.advance(monitor, 1);

        JSONArray reviewsArray = reviews.getJSONArray("reviews");
        
        int shipItCount = 0;

        for (int i = 0; i < reviewsArray.length(); i++) {

            JSONObject jsonReview = reviewsArray.getJSONObject(i);
            int reviewId = jsonReview.getInt("id");
            int totalResults = new JSONObject(httpClient.executeGet("/api/review-requests/" + taskData.getTaskId()+"/reviews/" + reviewId +"/diff-comments", monitor)).getInt("total_results");

            StringBuilder text = new StringBuilder();
            boolean shipit = jsonReview.getBoolean("ship_it");
            boolean appendWhiteSpace = false;
            if ( shipit ) {
                text.append("Ship it!");
                shipItCount++;
                appendWhiteSpace = true;
            }
            if ( jsonReview.getString("body_top").length() != 0  ) {
                if ( appendWhiteSpace )
                    text.append("\n\n");
                
                text.append(jsonReview.getString("body_top"));
                appendWhiteSpace = true;
            }
            if ( totalResults != 0 ) {
                if ( appendWhiteSpace )
                    text.append("\n\n");
                
                text.append(totalResults).append(" inline comments.");
                
                appendWhiteSpace = true;
            }
            if ( jsonReview.getString("body_bottom").length() != 0  ) {
                if ( appendWhiteSpace )
                    text.append("\n\n");

                text.append(jsonReview.getString("body_bottom"));
            }
            
            Comment2 comment = new Comment2();
            comment.setAuthor(taskData.getAttributeMapper().getTaskRepository().createPerson(
                    jsonReview.getJSONObject("links").getJSONObject("user").getString("title")));
            comment.setText(text.toString());

            sortedComments.put(
                    ReviewboardAttributeMapper.parseDateValue(jsonReview.getString("timestamp")),
                    comment);

        }
        
        TaskAttribute shipItAttribute = taskData.getRoot().createAttribute(Attribute.SHIP_IT.toString());
        shipItAttribute.setValue(String.valueOf(shipItCount));
        shipItAttribute.getMetaData().setLabel(Attribute.SHIP_IT.getDisplayName()).setType(Attribute.SHIP_IT.getAttributeType());
        shipItAttribute.getMetaData().setReadOnly(true).setKind(Attribute.SHIP_IT.isHidden() ? null : TaskAttribute.KIND_DEFAULT);

        int commentIndex = 1;
        
        for ( Map.Entry<Date, Comment2>  entry : sortedComments.entrySet() )
            entry.getValue().applyTo(taskData, commentIndex++, entry.getKey());
    }
    
    private void loadDiffsAndScreenshotsAsAttachments(TaskData taskData, TaskRepository taskRepository, IProgressMonitor monitor) throws ReviewboardException {
        
        List<Diff> diffs = loadDiffs(Integer.parseInt(taskData.getTaskId()), monitor);
        List<Screenshot> screenshots = loadScreenshots(Integer.parseInt(taskData.getTaskId()), monitor);
        
        if ( diffs.isEmpty() && screenshots.isEmpty() )
            return;
        
        int mostRecentRevision = diffs.size();

        for (Diff diff : diffs) {
            TaskAttribute attribute = taskData.getRoot().createAttribute(TaskAttribute.PREFIX_ATTACHMENT + diff.getRevision());
            TaskAttachmentMapper mapper = TaskAttachmentMapper.createFrom(attribute);
            mapper.setFileName(diff.getName());
            mapper.setDescription(diff.getName());
            mapper.setAuthor(taskRepository.createPerson(taskData.getRoot().getAttribute(ReviewboardAttributeMapper.Attribute.SUBMITTER.toString()).getValue()));
            mapper.setCreationDate(diff.getTimestamp());
            mapper.setAttachmentId(Integer.toString(diff.getId()));
            mapper.setPatch(Boolean.TRUE);
            mapper.setDeprecated(diff.getRevision() != mostRecentRevision);
            mapper.setLength(ReviewboardAttachmentHandler.ATTACHMENT_SIZE_UNKNOWN);
            mapper.applyTo(attribute);
            
            attribute.createAttribute(ReviewboardAttachmentHandler.ATTACHMENT_ATTRIBUTE_REVISION).setValue(String.valueOf(diff.getRevision()));
        }
        
        int attachmentIndex = mostRecentRevision;
        
        for ( Screenshot screenshot : screenshots ) {
  
            TaskAttribute attribute = taskData.getRoot().createAttribute(TaskAttribute.PREFIX_ATTACHMENT + ++ attachmentIndex);
            TaskAttachmentMapper mapper = TaskAttachmentMapper.createFrom(attribute);
            mapper.setFileName(screenshot.getFileName());
            mapper.setDescription(screenshot.getCaption());
            mapper.setAttachmentId(Integer.toString(screenshot.getId()));
            mapper.setLength(ReviewboardAttachmentHandler.ATTACHMENT_SIZE_UNKNOWN);
            mapper.setContentType(screenshot.getContentType());
            mapper.setUrl(stripPathFromLocation() + screenshot.getUrl());
            mapper.applyTo(attribute);
        }
        
    }

    public String stripPathFromLocation() throws ReviewboardException {
        
        try {
            URI uri = new URI(location.getUrl());
            uri.getPath();
            return location.getUrl().substring(0, location.getUrl().length() - uri.getPath().length());
        } catch (URISyntaxException e) {
            throw new ReviewboardException("Unable to retrive host from the location", e);
        }
    }

    public List<Repository> getRepositories(IProgressMonitor monitor) throws ReviewboardException {
        return reviewboardReader.readRepositories(httpClient.executeGet("/api/repositories/", monitor));
    }

    public List<User> getUsers(IProgressMonitor monitor) throws ReviewboardException {
        return reviewboardReader.readUsers(httpClient.executeGet("/api/users/", monitor));
    }

    public List<ReviewGroup> getReviewGroups(IProgressMonitor monitor) throws ReviewboardException {
        return reviewboardReader.readGroups(httpClient.executeGet("/api/groups/", monitor));
    }

    public List<ReviewRequest> getReviewRequests(String query, IProgressMonitor monitor)
            throws ReviewboardException {
        // TODO - should this be /api/review-requests/ ? 
        return reviewboardReader.readReviewRequests(
                httpClient.executeGet("/api/json/reviewrequests/" + query, monitor));
    }
    
    private List<Diff> loadDiffs(int reviewRequestId, IProgressMonitor monitor) throws ReviewboardException {
        
        return reviewboardReader.readDiffs(httpClient.executeGet("/api/review-requests/" + reviewRequestId+"/diffs", monitor));
    }

    private List<Screenshot> loadScreenshots(int reviewRequestId, IProgressMonitor monitor) throws ReviewboardException {
        
        return reviewboardReader.readScreenshots(httpClient.executeGet("/api/review-requests/" + reviewRequestId+"/screenshots", monitor));
    }
    
    private List<Integer> getReviewRequestIds(String query, IProgressMonitor monitor)
            throws ReviewboardException {
        return reviewboardReader.readReviewRequestIds(
                httpClient.executeGet("/api/review-requests/" + query, monitor));
    }

    public ReviewRequest getReviewRequest(int reviewRequestId, IProgressMonitor monitor)
            throws ReviewboardException {
        return reviewboardReader.readReviewRequest(httpClient.executeGet(
                "/api/json/reviewrequests/"
                        + reviewRequestId + "/", monitor));
    }

    public List<Review> getReviews(int reviewRequestId, IProgressMonitor monitor)
            throws ReviewboardException {
        List<Review> result = reviewboardReader.readReviews(
                httpClient.executeGet("/api/json/reviewrequests/" + reviewRequestId + "/reviews/",
                        monitor));

        for (Review review : result) {
            sortCommentsByLine(review);
        }

        return result;
    }

    private void sortCommentsByLine(Review review) {
        Collections.sort(review.getComments(), new Comparator<Comment>() {
            public int compare(Comment comment1, Comment comment2) {
                return ((Integer) comment1.getFirstLine()).compareTo(comment2.getFirstLine());
            }
        });
    }

    public boolean hasRepositoryData() {
        return (clientData.lastupdate != 0);
    }

    public void updateRepositoryData(boolean force, IProgressMonitor monitor) throws ReviewboardException {
        if (hasRepositoryData() && !force) {
            return;
        }
        
        monitor.beginTask("Refreshing repository data", 3);

        try {
            clientData.setGroups(getReviewGroups(monitor));
            Policy.advance(monitor, 1);

            clientData.setUsers(getUsers(monitor));
            Policy.advance(monitor, 1);

            clientData.setRepositories(getRepositories(monitor));
            Policy.advance(monitor, 1);
            
            clientData.lastupdate = new Date().getTime();
        } finally  {
            monitor.done();
        }
    }

    public void performQuery(TaskRepository repository, IRepositoryQuery query,
            TaskDataCollector collector, IProgressMonitor monitor) throws CoreException {
        try {
            List<ReviewRequest> reviewRequests = getReviewRequests(query.getUrl(), monitor);
            for (ReviewRequest reviewRequest : reviewRequests) {
                TaskData taskData = getTaskDataForReviewRequest(repository, reviewRequest);
                collector.accept(taskData);
            }
        } catch (ReviewboardException e) {
            throw new CoreException(Status.CANCEL_STATUS);
        }
    }

    private TaskData getTaskDataForReviewRequest(TaskRepository taskRepository,
            ReviewRequest reviewRequest) {
        String summary = reviewRequest.getSummary();
        String id = String.valueOf(reviewRequest.getId());
        String owner = reviewRequest.getSubmitter().getUsername();
        Date creationDate = reviewRequest.getTimeAdded();
        Date dateModified = reviewRequest.getLastUpdated();
        String description = reviewRequest.getDescription();
        ReviewRequestStatus status = reviewRequest.getStatus();

        TaskData taskData = new TaskData(new ReviewboardAttributeMapper(taskRepository),
                ReviewboardCorePlugin.REPOSITORY_KIND, location.getUrl(), id);
        taskData.setPartial(true);

        ReviewboardTaskMapper mapper = new ReviewboardTaskMapper(taskData, true);
        mapper.setTaskKey(id);
        mapper.setCreationDate(creationDate);
        // intentionally left out
//        mapper.setModificationDate(dateModified);
        mapper.setSummary(summary);
        mapper.setOwner(owner);
        mapper.setDescription(description);
        mapper.setTaskUrl(ReviewboardUtil.getReviewRequestUrl(taskRepository.getUrl(), id));
        mapper.setStatus(status.name().toLowerCase());
        if ( status != ReviewRequestStatus.PENDING )
            mapper.setCompletionDate(dateModified);
        
        return taskData;
    }

    public byte[] getRawDiff(int reviewRequestId, int diffRevision, IProgressMonitor monitor) throws ReviewboardException {
        
        return httpClient.executeGetForBytes("/api/review-requests/" + reviewRequestId + "/diffs/" + diffRevision +"/","text/x-patch", monitor);
    }
    
    public byte[] getScreenshot(String url, IProgressMonitor monitor) throws ReviewboardException {
        return httpClient.executeGetForBytes("/" + url, "image/*", monitor);
    }

    public IStatus validate(String username, String password, IProgressMonitor monitor) {

        try {
            
            if ( !httpClient.apiEntryPointExist(monitor) )
                return new Status(IStatus.ERROR, ReviewboardCorePlugin.PLUGIN_ID, "Repository not found. Please make sure that the path to the repository correct and the  server version is at least 1.5");
            
            Policy.advance(monitor, 1);
            
            httpClient.login(username, password, monitor);
            
            Policy.advance(monitor, 1);
            
            ServerInfo serverInfo = reviewboardReader.readServerInfo(httpClient.executeGet("/api/info", monitor));
            
            Policy.advance(monitor, 1);
            
            if ( !serverInfo.isAtLeast(1, 5) )
                return new Status(IStatus.ERROR, ReviewboardCorePlugin.PLUGIN_ID, "The version " + serverInfo.getProductVersion() + " is not supported. Please use a repository version of 1.5 or newer.");
            
            return Status.OK_STATUS;
        } catch ( ReviewboardException e ) {
            return new Status(IStatus.ERROR, ReviewboardCorePlugin.PLUGIN_ID, e.getMessage(), e);
        } catch (Exception e) {
            return new Status(IStatus.ERROR, ReviewboardCorePlugin.PLUGIN_ID, "Unexpected exception : " + e.getMessage(), e);
        } finally {
            monitor.done();
        }
    }
    
    public List<Integer> getReviewsIdsChangedSince(Date timestamp, IProgressMonitor monitor) throws ReviewboardException {
        
        try {
            // TODO: extract into a ReviewRequestQuery
            Assert.isNotNull(timestamp);
            
            String query = "?last-updated-from=" + URLEncoder.encode( ReviewboardAttributeMapper.newIso86011DateFormat().format(timestamp), "UTF-8");
            return getReviewRequestIds( query, monitor);
            
        } catch (UnsupportedEncodingException e) {
            throw new ReviewboardException("Failed encoding the query url", e);
        }
        
    }

    private static class Comment2 {

        private IRepositoryPerson author;
        private String text;

        public void setAuthor(IRepositoryPerson author) {
            this.author = author;
        }

        public void setText(String text) {
            this.text = text;
        }

        public void applyTo(TaskData taskData, int index, Date creationDate) {

            TaskAttribute attribute = taskData.getRoot().createAttribute(TaskAttribute.PREFIX_COMMENT + index);

            TaskCommentMapper mapper = new TaskCommentMapper();
            mapper.setCommentId(String.valueOf(index));
            mapper.setCreationDate(creationDate);
            mapper.setAuthor(author);
            mapper.setText(text);
            mapper.setNumber(index);

            mapper.applyTo(attribute);
            
        }

    }
}
