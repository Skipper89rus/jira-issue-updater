package com.transas.jenkins.plugins.jiraissueupdater;

import hudson.plugins.jira.JiraSite;

public class UpdaterSttings {

   public String jenkinsRootUrl;

   public String groupVisibility;
   public String roleVisibility;

   public String projectName;

   public String buildNum;
   public String buildLocation;

   public String dateTimePattern;
   public String fixPattern;

   public void addSttings(String jenkinsRootUrl, JiraSite site) {
      this.jenkinsRootUrl = jenkinsRootUrl;

      this.groupVisibility = site.groupVisibility;
      this.roleVisibility  = site.roleVisibility;
      this.dateTimePattern = site.getDateTimePattern();
   }
}
