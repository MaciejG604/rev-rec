package muni.fi.revrec.api;

import com.mashape.unirest.http.exceptions.UnirestException;
import muni.fi.revrec.common.GerritService;
import muni.fi.revrec.common.GitService;
import muni.fi.revrec.common.data.DataLoader;
import muni.fi.revrec.common.exception.ReviewerRecommendationException;
import muni.fi.revrec.model.filePath.FilePathDAO;
import muni.fi.revrec.model.pullRequest.PullRequest;
import muni.fi.revrec.model.pullRequest.PullRequestDAO;
import muni.fi.revrec.recommendation.bayesrec.BayesRec;
import muni.fi.revrec.recommendation.revfinder.RevFinder;
import muni.fi.revrec.recommendation.reviewbot.ReviewBot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * REST interface of the system.
 *
 * @author Jakub Lipcak, Masaryk University
 */
@RestController
@RequestMapping(path = "/api")
public class ReviewerController {

    private enum RecommendationMethod {
        REVIEWBOT, REVFINDER, BAYES
    }

    @Autowired
    private RevFinder revFinder;

    @Autowired
    private ReviewBot reviewBot;

    @Autowired
    private BayesRec bayesRec;

    @Autowired
    private GerritService gerritService;

    @Autowired
    private PullRequestDAO pullRequestDAO;

    @Autowired
    private FilePathDAO filePathDAO;

    @Autowired
    DataLoader dataLoader;

    @RequestMapping(value = "/reviewers-recommendation", method = RequestMethod.GET)
    ResponseEntity<?> recommend(@RequestParam(value = "gerritChangeNumber", required = true) String gerritChangeNumber,
                                @RequestParam(value = "recommendationMethod", required = false) RecommendationMethod recommendationMethod) {
        PullRequest pullRequest = gerritService.getPullRequest(gerritChangeNumber);

        if (recommendationMethod != null) {
            switch (recommendationMethod) {
                case REVIEWBOT:
                    return ResponseEntity.ok(reviewBot.recommend(pullRequest));
                case REVFINDER:
                    return ResponseEntity.ok(revFinder.recommend(pullRequest));
                case BAYES:
                    return ResponseEntity.ok(bayesRec.recommend(pullRequest));
            }
        }

        return ResponseEntity.ok(revFinder.recommend(pullRequest));
    }

    @RequestMapping(value = "/recommend-reviewer", method = RequestMethod.GET)
    ResponseEntity<?> recommendReviewer(@RequestParam(value = "project-name", required = true) String projectName,
                                @RequestParam(value = "project-url", required = true) String projectUrl,
                                @RequestParam(value = "pr-id", required = true) String pullRequestId,
                                @RequestParam(value = "method", required = false) RecommendationMethod recommendationMethod) {
        Optional<PullRequest> pullRequest = Optional.empty();
        try {
            pullRequest = dataLoader.fetchPullRequest(pullRequestId, projectName, projectUrl);
        } catch (UnirestException e) {
            throw new RuntimeException(e);
        }


        if (pullRequest.isPresent()) {
            if (recommendationMethod != null) {
                switch (recommendationMethod) {
                    case REVIEWBOT:
                        return ResponseEntity.ok(reviewBot.recommend(pullRequest.get()));
                    case REVFINDER:
                        revFinder = new RevFinder(pullRequestDAO, false, 12, projectName, false);
                        return ResponseEntity.ok(revFinder.recommend(pullRequest.get()));
                    case BAYES:
                        try {
                            ResponseEntity.ok(bayesRec.recommend(pullRequest.get()));
                        } catch (ReviewerRecommendationException ignored) {
                            bayesRec = new BayesRec(pullRequestDAO, filePathDAO, true, 12, projectName, false);
                            bayesRec.buildModel();
                            return ResponseEntity.ok(bayesRec.recommend(pullRequest.get()));
                        }
                }
            }
            revFinder = new RevFinder(pullRequestDAO, false, 12, projectName, false);

            return ResponseEntity.ok(revFinder.recommend(pullRequest.get()));
        }

        return ResponseEntity.badRequest().body("No PR with this change number");
    }
}
