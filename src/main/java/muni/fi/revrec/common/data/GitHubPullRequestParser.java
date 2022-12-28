package muni.fi.revrec.common.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import muni.fi.revrec.model.filePath.FilePath;
import muni.fi.revrec.model.reviewer.Developer;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class GitHubPullRequestParser implements PullRequestParser {

    private String projectUrl;

    private String gitHubToken;

    private JsonObject jsonObject;
    private String projectName;

    @Override
    public Set<FilePath> getFilePaths() {
        int changedFiles = jsonObject.get("changed_files").getAsInt();

        Set<FilePath> result = new HashSet<>();
        HttpResponse<String> jsonResponse = null;
        for (int x = 1; true; x++) {
            try {
                jsonResponse = Unirest.get(projectUrl + "/pulls/" + getChangeNumber() + "/files")
                        .header("Authorization", "token " + gitHubToken)
                        .queryString("page", x)
                        .asString();
            } catch (UnirestException e) {
                throw new RuntimeException();
            }
            String json = jsonResponse.getBody();
            if(json.equals("[]")){
                return result;
            }

            if(json.equals("{\"message\":\"Not Found\",\"documentation_url\":\"https://developer.github.com/v3/pulls/#list-pull-requests-files\"}")){
                return null;
            }

            for (JsonElement jsonElement : new JsonParser().parse(json).getAsJsonArray()) {
                FilePath filePath = new FilePath();
                filePath.setLocation(((JsonObject) jsonElement).get("filename").getAsString());
                result.add(filePath);
            }
        }
    }

    private JsonArray getRawFilePaths() {
        JsonArray result = new JsonArray();

        HttpResponse<String> jsonResponse;
        for (int x = 1; true; x++) {
            try {
                jsonResponse = Unirest.get(projectUrl + "/pulls/" + getChangeNumber() + "/files")
                        .header("Authorization", "token " + gitHubToken)
                        .queryString("page", x)
                        .asString();
            } catch (UnirestException e) {
                throw new RuntimeException();
            }
            String json = jsonResponse.getBody();
            if(json.equals("[]")){
                return result;
            }

            if(json.equals("{\"message\":\"Not Found\",\"documentation_url\":\"https://developer.github.com/v3/pulls/#list-pull-requests-files\"}")){
                throw new RuntimeException();
            }

            for (JsonElement jsonElement : new JsonParser().parse(json).getAsJsonArray()) {
                JsonObject filePath = new JsonObject();
                filePath.add("location", jsonElement.getAsJsonObject().get("filename"));
                result.add(filePath);
            }
        }
    }

    @Override
    public String getChangeId() {
        return jsonObject.get("id").getAsString();
    }

    @Override
    public Integer getChangeNumber() {
        return jsonObject.get("number").getAsInt();
    }

    @Override
    public Developer getOwner() {
        return parseDeveloper(jsonObject.get("user"));
    }

    @Override
    public String getSubProject() {
        return "";
    }

    @Override
    public Long getTimeStamp() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).parse(jsonObject.get("created_at").getAsString()).getTime();
        } catch (ParseException e) {
            throw new RuntimeException();
        }
    }

    @Override
    public Set<Developer> getReviewers() {
        Set<Developer> result = new HashSet<>();
        int changeNumber = getChangeNumber();
        //result.add(parseDeveloper(jsonObject.get("merged_by")));

        HttpResponse<String> jsonResponse;
        String json;

        try {
            jsonResponse = Unirest.get(projectUrl + "/issues/" + getChangeNumber() + "/comments")
                    .header("Authorization", "token " + gitHubToken)
                    .asString();
        } catch (UnirestException e) {
            throw new RuntimeException();
        }

        json = jsonResponse.getBody();

        for (JsonElement jsonElement : new JsonParser().parse(json).getAsJsonArray()) {
            Developer developer = parseDeveloper(jsonElement.getAsJsonObject().get("user"));
            if (!developer.getName().contains("bot")) {
                result.add(developer);
            }
        }

        try {
            jsonResponse = Unirest.get(projectUrl + "/pulls/" + getChangeNumber() + "/reviews")
                    .header("Authorization", "token " + gitHubToken)
                    .asString();
        } catch (UnirestException e) {
            throw new RuntimeException();
        }

        json = jsonResponse.getBody();

        if(json.equals("{\"message\":\"Not Found\",\"documentation_url\":\"https://developer.github.com/v3\"}")){
            return result;
        }

        for (JsonElement jsonElement : new JsonParser().parse(json).getAsJsonArray()) {
            Developer developer = parseDeveloper(jsonElement.getAsJsonObject().get("user"));
            if (!developer.getName().contains("bot")) {
                result.add(developer);
            }
        }

        return result;
    }

    private JsonArray getRawReviewers() {
        JsonArray result = new JsonArray();
        HttpResponse<String> jsonResponse;
        String json;

        try {
            jsonResponse = Unirest.get(projectUrl + "/issues/" + getChangeNumber() + "/comments")
                    .header("Authorization", "token " + gitHubToken)
                    .asString();
        } catch (UnirestException e) {
            throw new RuntimeException();
        }

        json = jsonResponse.getBody();

        for (JsonElement jsonElement : new JsonParser().parse(json).getAsJsonArray()) {
            JsonElement userElement = jsonElement.getAsJsonObject().get("user");

            result.add(deleteUnusedFields((userElement)));
        }

        try {
            jsonResponse = Unirest.get(projectUrl + "/pulls/" + getChangeNumber() + "/reviews")
                    .header("Authorization", "token " + gitHubToken)
                    .asString();
        } catch (UnirestException e) {
            throw new RuntimeException();
        }

        json = jsonResponse.getBody();

        for (JsonElement jsonElement : new JsonParser().parse(json).getAsJsonArray()) {
            JsonElement userElement = jsonElement.getAsJsonObject().get("user");

            result.add(deleteUnusedFields((userElement)));
        }

        return result;
    }

    @Override
    public void setJsonObject(JsonObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    private Developer parseDeveloper(JsonElement jsonElement) {
        Developer developer = new Developer();
        developer.setAccountId(((JsonObject) jsonElement).get("id").getAsString());
        developer.setName(((JsonObject) jsonElement).get("login").getAsString());
        developer.setEmail("");
        developer.setAvatar(((JsonObject) jsonElement).get("avatar_url").getAsString());
        return developer;
    }

    private JsonObject deleteUnusedFields(JsonElement jsonElement) {
        JsonObject result = new JsonObject();

        result.add("accountId", jsonElement.getAsJsonObject().get("id"));
        result.add("email", new JsonPrimitive(""));
        result.add("name", jsonElement.getAsJsonObject().get("login"));
        result.add("avatar", jsonElement.getAsJsonObject().get("avatar_url"));

        return result;
    }

    public String getGitHubToken() {
        return gitHubToken;
    }

    public void setGitHubToken(String gitHubToken) {
        this.gitHubToken = gitHubToken;
    }

    public String getProjectUrl() {
        return projectUrl;
    }

    public void setProjectUrl(String projectUrl) {
        this.projectUrl = projectUrl;
    }

    @Override
    public void appendToDataFile(String projectName) {
            try {
                File dataFile = new File("rev-rec-data" + File.separator + projectName + ".json");
                JsonObject jsonToAppend = new JsonObject();

                jsonToAppend.add("subProject", new JsonPrimitive(getSubProject()));
                jsonToAppend.add("changeId", new JsonPrimitive(getChangeId()));
                jsonToAppend.add("changeNumber", new JsonPrimitive(getChangeNumber()));
                jsonToAppend.add("timestamp", new JsonPrimitive(getTimeStamp()));
                jsonToAppend.add("reviewers", getRawReviewers());
                jsonToAppend.add("owner", deleteUnusedFields(jsonObject.get("user")));
                jsonToAppend.add("filePaths", getRawFilePaths());

                if (!dataFile.exists()) {
                    Files.write(dataFile.toPath(), ("[" + jsonToAppend).getBytes());
                } else {
                    Files.write(dataFile.toPath(), ("," + jsonToAppend).getBytes(), StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {}
    }
}
