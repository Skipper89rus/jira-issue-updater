package com.transas.jenkins.plugin.jiraissueupdater;

import static java.lang.String.format;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;

public class CommentConstructor
{

   private UpdaterSttings settings;

   public CommentConstructor(UpdaterSttings settings)
   {
      this.settings = settings;
   }

   /**
    * Creates a comment to be used in JIRA for the build.
    * Will be used, when UpdateAction is UA_COMMENT.
    */
   public String createFullComment(IssueEx issue, boolean coloredAuthor)
   {
      if (StringUtils.isBlank(settings.buildNum))
      {
         return "";
      }

      String buildMsg = "Build " + settings.buildNum;
      if (StringUtils.isNotBlank(settings.projectName) && StringUtils.isNotBlank(settings.buildLocation))
      {
         // Добавляем в комментарий ссылку на сборку
         Path buildLocation = Paths.get(settings.buildLocation, String.format("%1$s.%2$s", settings.projectName, settings.buildNum));
         buildMsg = String.format("Build [%1$s|%2$s]", settings.buildNum, buildLocation.toString());
      }

      return format("Work commited. %1$s%n%2$s", buildMsg, createChangeComment(issue, coloredAuthor));
   }

   /**
    * Creates a comment to be used in JIRA for the build.
    * Will be used, when UpdateAction is UA_RESOLVE or UA_LOG_WORK.
    */
   public String createChangeComment(IssueEx issue, boolean coloredAuthor)
   {
      StringBuilder description = new StringBuilder();

      appendAuthorToDescription(issue, coloredAuthor, description);
      appendChangeTimestampToDescription(issue, description);

      if (StringUtils.isNotBlank(description.toString()))
      {
         description.append(": ");
      }

      description.append(issue.comment);
      description.append("\n");

      return description.toString();
   }

   private void appendAuthorToDescription(IssueEx issue, boolean coloredAuthor, StringBuilder description)
   {
      if (issue.author == null || issue.authorId == null)
      {
         return;
      }

      String userName = issue.author.getDisplayName();
      if (StringUtils.isBlank(userName))
      {
         return;
      }

      Matcher m = Pattern.compile("(?<name>\\w+)[\\s]*,[\\s]*(?<surname>\\w+)").matcher(userName);
      if (m.find() && m.groupCount() == 2 && StringUtils.isNotBlank(m.group("name")) && StringUtils.isNotBlank(m.group("surname")))
      {
         // Write full name with red color. Example: Name Surname
         userName = WordUtils.capitalize(format("%1$s %2$s", m.group("name"), m.group("surname")));
      }

      if (coloredAuthor)
      {
         description.append(
               format("[*{color:#d04437}%1$s{color}*|https://jira.transas.com/secure/ViewProfile.jspa?name=%2$s]", userName, issue.authorId));
      }
      else
      {
         description.append(userName);
      }
   }

   private void appendChangeTimestampToDescription(IssueEx issue, StringBuilder description)
   {
      if (issue.timestamp <= 0)
      {
         return;
      }

      DateFormat df = null;
      if (StringUtils.isNotBlank(settings.dateTimePattern))
      {
         df = new SimpleDateFormat(settings.dateTimePattern);
      }
      else
      {
         // default format for current locale
         df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());
      }

      Date changeDate = new Date(issue.timestamp);

      description.append(" at ");
      description.append(df.format(changeDate));
   }
}
