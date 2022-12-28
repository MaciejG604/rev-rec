package muni.fi.revrec.common.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import muni.fi.revrec.common.exception.ReviewerRecommendationException;
import muni.fi.revrec.model.filePath.FilePath;
import muni.fi.revrec.model.filePath.FilePathDAO;
import muni.fi.revrec.model.project.Project;
import muni.fi.revrec.model.project.ProjectDAO;
import muni.fi.revrec.model.pullRequest.PullRequest;
import muni.fi.revrec.model.pullRequest.PullRequestDAO;
import muni.fi.revrec.model.reviewer.Developer;
import muni.fi.revrec.model.reviewer.DeveloperDAO;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This class is used to load data from different sources and stores them in the database.
 *
 * @author Jakub Lipcak, Masaryk University
 */
@Service
public class DataLoader {

    private final Log logger = LogFactory.getLog(this.getClass());

    @Autowired
    private GerritPullRequestParser gerritPullRequestParser;

    @Autowired
    private GitHubPullRequestParser gitHubPullRequestParser;

    @Autowired
    private ProjectDAO projectDAO;

    @Autowired
    private FilePathDAO filePathDAO;

    @Autowired
    private PullRequestDAO pullRequestDAO;

    @Autowired
    private DeveloperDAO developerDAO;

    private String gitHubToken;

    private String projectName;
    private String projectUrl;

    public void fetchData(String projectName, String projectUrl) throws UnirestException {
        File dataFile = new File("rev-rec-data" + File.separator + projectName + ".json");

        try {
            Files.delete(dataFile.toPath());
        } catch (IOException ignored) {}

        this.projectName = projectName;
        this.projectUrl = projectUrl;

        int tokenIndex = 0;
        String[] tokens = {"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"};  //insert valid github tokens

        Project project = createIfNotExist(projectName, projectUrl);
        boolean isGit = projectUrl.contains("github");

        RequestConfig globalConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES).build();
        HttpClient httpclient = HttpClients.custom().setDefaultRequestConfig(globalConfig).build();
        Unirest.setHttpClient(httpclient);

        int start = 0;
        int iteration = 6;
        int totalPullsInOneRequest = 100;
        int processedPullRequests = 0;
        try {
            do {
                String json = "";
                gitHubToken = tokens[0];
                try {
                    PullRequestParser pullRequestParser;

                    if (isGit) {
                        HttpResponse<String> jsonResponse = Unirest.get(projectUrl + "/pulls")
                                .queryString("state", "closed")
                                .queryString("per_page", totalPullsInOneRequest)
                                .queryString("page", iteration + 1)
                                .header("Authorization", "token " + gitHubToken)
                                .asString();
                        json = jsonResponse.getBody();
                        pullRequestParser = gitHubPullRequestParser;
                        gitHubPullRequestParser.setGitHubToken(gitHubToken);
                        gitHubPullRequestParser.setProjectUrl(projectUrl);
                    } else {
                        HttpResponse<String> jsonResponse = Unirest.get(projectUrl + "/changes/")
                                .queryString("start", String.valueOf((iteration * totalPullsInOneRequest) + start))
                                .queryString("n", totalPullsInOneRequest)
                                .queryString("q", "status:merged")
                                .queryString("o", "DETAILED_LABELS")
                                .queryString("o", "DETAILED_ACCOUNTS")
                                .queryString("o", "CURRENT_REVISION")
                                .queryString("o", "CURRENT_COMMIT")
                                .queryString("o", "CURRENT_FILES")
                                .queryString("q", "limit:" + totalPullsInOneRequest)
                                .asString();
                        json = jsonResponse.getBody().substring(5);
                        pullRequestParser = gerritPullRequestParser;
                    }

                    for (int x = 0; x < totalPullsInOneRequest; x++) {

                        processedPullRequests = (iteration * totalPullsInOneRequest) + x + start;
                        JsonObject jsonObject = null;
                        if (isGit) {
                            if (((JsonObject) ((JsonArray) new JsonParser().parse(json)).get(x)).get("state").isJsonNull() || !((JsonObject) ((JsonArray) new JsonParser().parse(json)).get(x)).get("state").getAsString().equals("closed")) {
                                System.out.println("not merged");
                                continue;
                            } else {
                                jsonObject = new JsonParser().parse(getGithubPullRequestDetail(
                                        ((JsonObject) ((JsonArray) new JsonParser().parse(json)).get(x)).get("number").getAsString())).getAsJsonObject();
                            }
                        } else {
                            jsonObject = ((JsonArray) ((JsonArray) new JsonParser().parse(json)).get(0)).get(x).getAsJsonObject();
                        }

                        pullRequestParser.setJsonObject(jsonObject);
                        process(pullRequestParser, project, true);
                        System.out.print(processedPullRequests);
                        System.out.print(", ");
                    }
                    System.out.print("\n");
                    iteration++;
                } catch (NullPointerException | ClassCastException ex) {
                    if (isGit) {
                        tokenIndex++;
                        System.out.println("Request rate limit reached, wait for 1 hour");
                        try {
                            TimeUnit.HOURS.sleep(1);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        throw ex;
                    }
                }

            } while (true);
        } catch (ReviewerRecommendationException ex) {
            logger.info("Processed pull requests: " + processedPullRequests);
            logger.error(ex.getMessage());
        }

        try {
            Files.write(dataFile.toPath(), new byte[]{']'});
        } catch (IOException ignored) {}
    }

    public Optional<PullRequest> fetchPullRequest(String prChangeId, String projectName, String projectUrl) throws UnirestException {
        this.projectName = projectName;
        this.projectUrl = projectUrl;

        String token = "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";

        Project project = createIfNotExist(projectName, projectUrl);
        boolean isGit = projectUrl.contains("github");

        RequestConfig globalConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES).build();
        HttpClient httpclient = HttpClients.custom().setDefaultRequestConfig(globalConfig).build();
        Unirest.setHttpClient(httpclient);

        int start = 0;
        int iteration = 0;
        int totalPullsInOneRequest = 100;
        int processedPullRequests = 0;
        try {
            String json = "";
            gitHubToken = token;
            try {
                PullRequestParser pullRequestParser;

                if (isGit) {
                    HttpResponse<String> jsonResponse = Unirest.get(projectUrl + "/pulls/" + prChangeId)
                            .header("Authorization", "token " + gitHubToken)
                            .asString();
                    json = jsonResponse.getBody();
                    pullRequestParser = gitHubPullRequestParser;
                    gitHubPullRequestParser.setGitHubToken(gitHubToken);
                    gitHubPullRequestParser.setProjectUrl(projectUrl);
                } else {
                    throw new RuntimeException("Only GitHub PR fetching implemented!");
                }

                for (int x = 0; x < (isGit ? 30 : totalPullsInOneRequest); x++) {

                    processedPullRequests = (iteration * (isGit ? 30 : totalPullsInOneRequest)) + x + start;
                    JsonObject jsonObject = null;
                    if (isGit) {
                        jsonObject = new JsonParser().parse(getGithubPullRequestDetail(
                                ((JsonObject) new JsonParser().parse(json)).get("number").getAsString())).getAsJsonObject();
                    } else {
                        // jsonObject = ((JsonArray) ((JsonArray) new JsonParser().parse(json)).get(0)).get(x).getAsJsonObject();
                    }

                    pullRequestParser.setJsonObject(jsonObject);
                    return Optional.ofNullable(processNew(pullRequestParser, project));
                }
            } catch (NullPointerException | ClassCastException ex) {
                System.out.println("Error while fetching PR!");
            }
        } catch (ReviewerRecommendationException ex) {
            logger.info("Processed pull requests: " + processedPullRequests);
            logger.error(ex.getMessage());
        }

        return Optional.empty();
    }

    private PullRequest process(PullRequestParser pullRequestParser, Project project, boolean savePR) {
        PullRequest pr = pullRequestDAO.findByProjectNameAndChangeId(projectName, pullRequestParser.getChangeId());
        PullRequest pullRequest = new PullRequest();

        try {
            if (pr == null) {
                pullRequest.setChangeId(pullRequestParser.getChangeId());
                pullRequest.setChangeNumber(pullRequestParser.getChangeNumber());
                pullRequest.setSubProject(pullRequestParser.getSubProject());
                pullRequest.setTimestamp(pullRequestParser.getTimeStamp());
                pullRequest.setOwner(createIfNotExist(pullRequestParser.getOwner()));
                pullRequest.setProject(project);
                pullRequest.setReviewers(createIfNotExist(pullRequestParser.getReviewers()));
                if (pullRequest.getReviewers().contains(pullRequest.getOwner())) {
                    pullRequest.getReviewers().remove(pullRequest.getOwner());
                }
                if (pullRequest.getReviewers() == null || pullRequest.getReviewers().isEmpty() || pullRequest.getOwner() == null) {
                    return null;
                }
            } else {
                return null;
            }
        } catch (Exception ex) {
            logger.error("Parsing exception: " + ex.getMessage());
            return null;
        }

        if (savePR) {
            pullRequest = pullRequestDAO.save(pullRequest);
            Set<FilePath> filePaths = create(pullRequestParser.getFilePaths(), pullRequest);
            if (filePaths == null) {
                pullRequestDAO.delete(pullRequest);
            } else {
                pullRequestParser.appendToDataFile(projectName);
            }
        }

        return pullRequest;
    }

    private PullRequest processNew(PullRequestParser pullRequestParser, Project project) {
        PullRequest pullRequest = new PullRequest();

        try {
            pullRequest.setChangeId(pullRequestParser.getChangeId());
            pullRequest.setChangeNumber(pullRequestParser.getChangeNumber());
            pullRequest.setSubProject(pullRequestParser.getSubProject());
            pullRequest.setTimestamp(pullRequestParser.getTimeStamp());
            pullRequest.setOwner(createIfNotExist(pullRequestParser.getOwner()));
            pullRequest.setProject(project);
            pullRequest.setReviewers(createIfNotExist(pullRequestParser.getReviewers()));
            if (pullRequest.getReviewers().contains(pullRequest.getOwner())) {
                pullRequest.getReviewers().remove(pullRequest.getOwner());
            }
            if (pullRequest.getOwner() == null) {
                return null;
            }

            pullRequest.setFilePaths(pullRequestParser.getFilePaths());
            if (pullRequest.getFilePaths() == null) {
                return null;
            }
        } catch (Exception ex) {
            logger.error("Parsing exception: " + ex.getMessage());
            return null;
        }

        return pullRequest;
    }

    private Developer createIfNotExist(Developer developer) {
        Developer dev = developerDAO.findByAccountIdAndNameAndEmail(developer.getAccountId(), developer.getName(), developer.getEmail());
        if (dev == null) {
            return developerDAO.save(developer);
        }
        return dev;
    }

    private Set<Developer> createIfNotExist(Set<Developer> reviewers) {
        Set<Developer> result = new HashSet<>();
        for (Developer reviewer : reviewers) {
            Developer developer = developerDAO.findByAccountIdAndNameAndEmail(reviewer.getAccountId(), reviewer.getName(), reviewer.getEmail());
            if (developer == null) {
                result.add(developerDAO.save(reviewer));
            } else {
                result.add(developer);
            }
        }
        return result;
    }

    private Project createIfNotExist(String projectName, String projectUrl) {
        Optional<Project> p = projectDAO.findById(projectName);
        return p.orElseGet(() -> projectDAO.save(new Project(projectName, projectUrl)));
    }

    private Set<FilePath> create(Set<FilePath> filePaths, PullRequest pullRequest) {
        if (filePaths == null) {
            return null;
        }
        Set<FilePath> result = new HashSet<>();
        for (FilePath filePath : filePaths) {
            filePath.setPullRequest(pullRequest);
            result.add(filePathDAO.save(filePath));
        }

        return result;
    }

    private String getGithubPullRequestDetail(String number) throws UnirestException {
        HttpResponse<String> jsonResponse = Unirest.get(projectUrl + "/pulls/" + number)
                .header("Authorization", "token " + gitHubToken)
                .asString();
        return jsonResponse.getBody();
    }

    public void saveDataToFile(String fileName) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<PullRequest> obj = pullRequestDAO.findAll();

        mapper.writeValue(new File("rev-rec-data" + File.separator + fileName + ".json"), obj);
    }

    public void initDbFromJson(String projectName, String projectUrl) throws IOException {
        //delete existing data
        deleteData();

        //load from json
        ObjectMapper mapper = new ObjectMapper();
        List<PullRequest> obj = mapper.readValue(new File("rev-rec-data" + File.separator + projectName + ".json"), new TypeReference<List<PullRequest>>() {
        });


        Project project = createIfNotExist(projectName, projectUrl);
        //save in db
        for (PullRequest pullRequest : obj) {
            pullRequest.setOwner(createIfNotExist(pullRequest.getOwner()));
            pullRequest.setReviewers(createIfNotExist(pullRequest.getReviewers()));
            pullRequest.setProject(project);
            pullRequestDAO.save(pullRequest);
            create(pullRequest.getFilePaths(), pullRequest);
        }
    }

    public void deleteData() {
        pullRequestDAO.deleteAll();
        projectDAO.deleteAll();
        developerDAO.deleteAll();
    }

}

