package com.transas.jenkins.plugin.jiraissueupdater;

import hudson.plugins.jira.JiraSite;

public class UpdaterSttings
{
   public String groupVisibility;
   public String roleVisibility;

   public String projectName;

   public String buildNum;
   public String buildLocation;

   public String dateTimePattern;

   public void addSttings(JiraSite site)
   {
      this.groupVisibility = site.groupVisibility;
      this.roleVisibility = site.roleVisibility;
      this.dateTimePattern = site.getDateTimePattern();
   }
}
