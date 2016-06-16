package com.transas.jenkins.plugins.jiraissueupdater;

import hudson.model.Run;
import hudson.model.AbstractBuild;
import hudson.model.AbstractBuild.DependencyChange;
import hudson.plugins.jira.RunScmChangeExtractor;
import hudson.plugins.jira.JiraSite;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import com.transas.jenkins.plugins.jiraissueupdater.IssueUpdater.UpdateAction;

/**
 * Finds the commit messages that match JIRA issue EXTENDED_ISSUE_PATTERN
 * pattern and fills the IssuesEx objects.
 * Issues adds only if it exists in JIRA.
 */
public class IssuesCollector {

   // Пример: "! *MODELWIZARD-1234(1w 2d 3h 4m)"
   public static final Pattern EXTENDED_ISSUE_PATTERN = 
         Pattern.compile("(?<commitType>[\\+\\-\\!\\~\\=])?[\\s]*" + // характер изменений (+, -, !, ~, =)
                         "(?<doFix>\\*)?[\\s]*" + // резолвить/не резолвить
                         "(?<id>[A-Z\\n]+-[\\d|\\n]+)[\\s]*" + // issue id
                         "(?<timeSpent>\\([\\s]*" + // время целиком в скобочках, может быть пустым
                            "(?<w>\\d+w)?[\\s]*" + // недели
                            "(?<d>\\d+d)?[\\s]*" + // дни
                            "(?<h>\\d+h)?[\\s]*" + // часы
                            "(?<m>\\d+m)?[\\s]*" + // минуты
                         "\\))?");

   public static final Pattern TIME_SPENT_PATTERN = 
         Pattern.compile("\\([\\s]*" + // время целиком в скобочках, может быть пустым
                            "(?<w>\\d+w)?[\\s]*" + // недели
                            "(?<d>\\d+d)?[\\s]*" + // дни
                            "(?<h>\\d+h)?[\\s]*" + // часы
                            "(?<m>\\d+m)?[\\s]*" + // минуты
                         "\\)");

   /**
    * Finds the commit messages that match JIRA issue EXTENDED_ISSUE_PATTERN
    * pattern and fills the IssuesEx objects.
    */
   public void findIssues(Run<?, ?> build, PrintStream logger, JiraSite site, Set<IssueEx> issues) {

      Run<?, ?> prevBuild = build.getPreviousBuild();
      if (prevBuild != null) {
         // Issues that were carried forward.
         CarryOverAction a = prevBuild.getAction(CarryOverAction.class);
         if (a != null)
            for (IssueEx i : a.get() ) issues.add(i);

         // Issues fixed in dependencies
         for ( DependencyChange depc : RunScmChangeExtractor.getDependencyChanges(prevBuild).values() ) {
            for ( AbstractBuild<?, ?> b : depc.getBuilds() )
               addIssuesFromBuild(b, logger, issues);
         }
      }

      // Issues in this build
      addIssuesFromBuild(build, logger, issues);
   }

   private void addIssuesFromBuild(Run<?, ?> build, PrintStream logger, Set<IssueEx> issues) {
      for ( ChangeLogSet<? extends Entry> set : RunScmChangeExtractor.getChanges(build) ) {
         for (Entry change : set)
            addIssuesFromMsg(change, issues);
      }
   }

   public void addIssuesFromMsg(Entry change, Set<IssueEx> issues) {
      String msg = GitChangeHelper.getMsg(change);
      if (StringUtils.isBlank(msg))
         return;

      // Тут будут копиться найденные задачи, пока не попадется коммент
      ArrayList<IssueEx> foundIssues = new ArrayList<IssueEx>();

      Matcher m = EXTENDED_ISSUE_PATTERN.matcher(msg);
      int prevIssueEndIdx = 0;
      while ( m.find() ) {
         // Если комментарий не пуст, то добавляем к накопленным задачам данные
         if ( addDataToIssues(change, msg.substring(prevIssueEndIdx, m.start()), foundIssues) ) {
            // Копим заново
            issues.addAll(foundIssues);
            foundIssues.clear();
         }

         // Обязательно должен быть issue id
         if ( m.groupCount() < 1 || StringUtils.isBlank(m.group("id")) )
            continue;

         IssueEx issue = new IssueEx( m.group("id") );
         addTimeSpentToIssue(m.group("timeSpent"), issue);
         addActionToIssue(StringUtils.isNotBlank(m.group("doFix")), issue);
         
         foundIssues.add(issue);
         prevIssueEndIdx = m.end();
      }

      // Не забываем про последние задачи
      if ( addDataToIssues(change, msg.substring(prevIssueEndIdx), foundIssues) )
         issues.addAll(foundIssues);
   }

   /**
    * Adds data to issues if comment not empty.
    */
   private boolean addDataToIssues(Entry change, String comment, ArrayList<IssueEx> issues) {
      if ( StringUtils.isBlank(comment) )
         return false;

      for (IssueEx issue : issues) {
         issue.timestamp = change.getTimestamp();
         issue.authorId = GitChangeHelper.getAuthorId(change);
         issue.comment = comment.trim();
      }
      return true;
   }

   /**
    * Adds time spent to issue in JIRA format.
    */
   private void addTimeSpentToIssue(String timeSpent, IssueEx issue) {
      if ( StringUtils.isBlank(timeSpent) )
         return;

      Matcher m = TIME_SPENT_PATTERN.matcher(timeSpent);
      if ( m.find() ) {

         int weeks = getDateDigitFromGroup(m, "w");
         int days  = getDateDigitFromGroup(m, "d");
         int hours = getDateDigitFromGroup(m, "h");
         int mins  = getDateDigitFromGroup(m, "m");

         // Хардкод... получаем время в минутах
         issue.timeSpent = mins + hours*60 + days*24*60 + weeks*7*24*60;
      }
      
   }

   private int getDateDigitFromGroup(Matcher m, String dateToken) {
      try {
         return Integer.parseInt( m.group(dateToken).replace(dateToken, "") );
      } catch(Exception e) {
         return 0;
      }
   }

   /**
    * Adds update action to issue.
    */
   private void addActionToIssue(boolean doFix, IssueEx issue) {
      if (doFix)
         issue.action = UpdateAction.UA_RESOLVE; // timeSpent и коммент добавятся при резолве
      else if (issue.timeSpent > 0)
         issue.action = UpdateAction.UA_LOG_WORK; // сделается log work с комментом
      else
         issue.action = UpdateAction.UA_COMMENT; // просто коммент
   }
}
