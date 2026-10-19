package com.atenea.service.operations;

import java.util.*;

/** Verification warnings and confirmed degradation intentionally have different repeat policies. */
public final class OperationsAlertPolicy {
    private OperationsAlertPolicy(){ }
    public record IssueState(int consecutive,boolean notified) { }
    public record Decision(Map<String,IssueState> state,List<OperationsMonitoringIssue> alerts) { }
    public static Decision observe(Map<String,IssueState> previous,List<OperationsMonitoringIssue> issues,boolean canNotify) {
        var next=new LinkedHashMap<String,IssueState>();var alerts=new ArrayList<OperationsMonitoringIssue>();
        for(var issue:issues) {
            var old=previous.getOrDefault(issue.key(),new IssueState(0,false));
            int count=Math.min(3,old.consecutive()+1);
            boolean verification=issue.key().equals("ssh");
            boolean eligible=!issue.requiresConfirmation() || count>=3;
            boolean send=canNotify && eligible && (!verification || !old.notified());
            if(send)alerts.add(issue);
            next.put(issue.key(),new IssueState(count,verification && (old.notified() || send)));
        }
        return new Decision(Map.copyOf(next),List.copyOf(alerts));
    }
    public static Map<String,IssueState> unobserved(Map<String,IssueState> previous) {
        var next=new HashMap<String,IssueState>();
        previous.forEach((key,value)->next.put(key,new IssueState(0,value.notified())));
        return Map.copyOf(next);
    }
}
