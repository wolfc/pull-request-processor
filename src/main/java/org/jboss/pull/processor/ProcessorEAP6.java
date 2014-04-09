package org.jboss.pull.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.egit.github.core.Milestone;
import org.jboss.pull.shared.Util;
import org.jboss.pull.shared.connectors.RedhatPullRequest;
import org.jboss.pull.shared.connectors.common.Issue;
import org.jboss.pull.shared.connectors.bugzilla.Bug;
import org.jboss.pull.shared.spi.PullEvaluator.Result;

public class ProcessorEAP6 extends Processor {

    public ProcessorEAP6() throws Exception {
    }

    public void run() {
        System.out.println("Starting at: " + Util.getTime());

        try {
            final List<RedhatPullRequest> pullRequests = helper.getOpenPullRequests();

            for (RedhatPullRequest pullRequest : pullRequests) {
                Result result = processPullRequest(pullRequest);

                if (!result.isMergeable()) {
                    complain(pullRequest, result.getDescription());
                } else {
                    System.out.println("No complaints");
                }
            }
        } finally {
            System.out.println("Completed at: " + Util.getTime());
        }
    }

    public Result processPullRequest(RedhatPullRequest pullRequest) {
        System.out.println("\nProcessComplainer processing PullRequest '" + pullRequest.getNumber() + "' on repository '"
                + pullRequest.getOrganization() + "/" + pullRequest.getRepository() + "'");

        Result result = new Result(true);

        if (pullRequest.getMilestone().getTitle().equals("on hold")) {
            System.out.println("Github milestone 'on hold'. Do nothing.");
            return result;
        }

        result = bugComplaints(pullRequest, result);

        // Upstream checks
        if (pullRequest.isUpstreamRequired()) {
            if (pullRequest.hasRelatedPullRequestInDescription()) {
                // Do related PR checks
            } else {
                result.setMergeable(false);
                result.addDescription(ComplaintMessages.MISSING_UPSTREAM);
            }
        } else {
            System.out.println("Upstream not required");
        }

        return result;
    }

    protected Result bugComplaints(RedhatPullRequest pullRequest, Result result) {
        // Check for a bug
        if (!pullRequest.hasBugLinkInDescription()) {
            return result.changeResult(false, ComplaintMessages.MISSING_BUG);
        }

        // Make sure it's from BZ
        // TODO: Remove when JIRA compatibility is implemented
        if (!pullRequest.hasBZLinkInDescription()) {
            System.out.println("JIRA link in description. Currently unable to handle.");
            return result;
        }

        // Ensure only one bug has a valid target_release
        List<Issue> matches = getValidBugs(pullRequest);
        if (matches.isEmpty()) {
            return result.changeResult(false, ComplaintMessages.NO_MATCHING_BUG);
        } else if (matches.size() > 1) {
            return result;
        }
        // else if (matches.size() > 1) {
        // return result.changeResult(false, ComplaintMessages.MULTIPLE_MATCHING_BUGS);
        // }

        Bug bug = (Bug) matches.get(0);
        System.out.println("Using bug id '" + bug.getNumber() + "' as matching bug.");

        // Ensure only one target_release is set
        List<String> releases = new ArrayList<String>(bug.getFixVersions());
        if (releases.size() != 1) {
            return result.changeResult(false, ComplaintMessages.getMultipleReleases(bug.getNumber()));
        }

        String release = releases.get(0);

        // Bug target_milestone is set
        String milestoneTitle = null;
        if (isBugMilestoneSet(bug)) {
            milestoneTitle = release + "." + bug.getTargetMilestone();
        } else {
            result.changeResult(false, ComplaintMessages.getMilestoneNotSet(bug.getNumber()));
            milestoneTitle = pullRequest.getTargetBranchTitle();
        }

        // Verify milestone is usable
        Milestone milestone = findMilestone(milestoneTitle);
        if (!milestoneRule(milestone)) {
            return result.changeResult(false, ComplaintMessages.getMilestoneNotExistOrClosed(milestoneTitle));
        }

        // Establish if milestone can be changed
        if (pullRequest.getMilestone() == null) {
            setMilestone(pullRequest, milestone);
        } else if (pullRequest.getMilestone().getTitle().contains("x") && !milestone.getTitle().contains("x")) {
            setMilestone(pullRequest, milestone);
        } else if (!pullRequest.getMilestone().getTitle().equals(milestoneTitle)) {
            return result.changeResult(false,
                    ComplaintMessages.getMilestoneDoesntMatch(pullRequest.getMilestone().getTitle(), milestoneTitle));
        } else {
            System.out.println("Github milestone already matches bug milestone.");
        }

        return result;
    }

    protected boolean milestoneRule(Milestone milestone) {
        if (milestone == null || milestone.getState().equals("closed")) {
            return false;
        }
        return true;
    }

    protected String getBranchRegex(RedhatPullRequest pullRequest) {
        String branch = pullRequest.getTargetBranchTitle();
        List<String> branches = helper.getBranches();
        String branchRegex = null;
        if (branch.contains("x")) {
            if (branch.length() == 3) {
                branchRegex = branch.replace("x", "[" + branches.size() + "-9]+");
            } else if (branch.length() == 5) {
                // TODO: Possibly limit regex pattern based on closed github milestones or tags
                branchRegex = branch.replace("x", "[0-9]+");
            }
        }
        return branchRegex;
    }

    protected List<Issue> getValidBugs(RedhatPullRequest pullRequest) {
        String branchRegex = getBranchRegex(pullRequest);

        if (branchRegex != null) {
            List<Issue> bugs = pullRequest.getIssues();
            List<Issue> matches = new ArrayList<Issue>();
            for (Issue bug : bugs) {
                List<String> releases = new ArrayList<String>(bug.getFixVersions());
                for (String release : releases) {
                    if (Pattern.compile(branchRegex).matcher(release).find()) {
                        matches.add(bug);
                    }
                }
            }
            return matches;
        } else {
            System.out.println("Branch matching pattern is null. Branch value '" + pullRequest.getTargetBranchTitle()
                    + "' is unusable.");
        }

        return new ArrayList<Issue>();
    }

    protected boolean isBugMilestoneSet(Bug bug) {
        String milestone = bug.getTargetMilestone();
        if (!milestone.equals("---") && !milestone.equals("Pending")) {
            return true;
        }
        return false;
    }

    protected void setMilestone(RedhatPullRequest pullRequest, Milestone milestone) {
        if (!DRY_RUN) {
            pullRequest.setMilestone(milestone);
        }
        postComment(pullRequest, "Milestone changed to '" + milestone.getTitle() + "'");
    }

    /**
     * Finds a github milestone. Returns null if milestone doesn't exist
     *
     * @param title
     * @return - Milestone found or null
     */
    protected Milestone findMilestone(String title) {
        List<Milestone> milestones = helper.getGithubMilestones();

        for (Milestone milestone : milestones) {
            if (milestone.getTitle().equals(title)) {
                return milestone;
            }
        }

        return null;
    }

}