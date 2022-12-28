package muni.fi.revrec.common.data;

import com.google.gson.JsonObject;
import muni.fi.revrec.model.filePath.FilePath;
import muni.fi.revrec.model.reviewer.Developer;

import java.util.Set;

public interface PullRequestParser {

    Set<Developer> getReviewers();

    Set<FilePath> getFilePaths();

    String getChangeId();

    Integer getChangeNumber();

    Developer getOwner();

    String getSubProject();

    Long getTimeStamp();

    void setJsonObject(JsonObject jsonObject);

    void appendToDataFile(String projectName);
}
