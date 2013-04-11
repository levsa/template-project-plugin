package hudson.plugins.templateproject;

import hudson.Extension;
import hudson.Launcher;
import hudson.matrix.MatrixProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.JobProperty;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Project;
import hudson.model.StringParameterValue;
import hudson.security.AccessControlled;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Messages;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class ProxyBuilder extends Builder {

	private final String projectName;
    private List<StringParameterValue> parameterValues;

	@DataBoundConstructor
	public ProxyBuilder(String projectName) {
	    this.projectName = projectName;
	    this.parameterValues = new ArrayList<StringParameterValue>();
	    this.parameterValues.add(new StringParameterValue("foo", "bar"));
	    AbstractProject p = (AbstractProject) Hudson.getInstance().getItem(projectName);
	    if (p != null && p.isParameterized()) {
	        JobProperty property = p.getProperty(ParametersDefinitionProperty.class);
	        if (property != null) {
	            ParametersDefinitionProperty paramProperty = (ParametersDefinitionProperty) property;
	            for (String name : paramProperty.getParameterDefinitionNames()) {
	                ParameterDefinition definition = paramProperty.getParameterDefinition(name);
	                String value = "";
	                if (definition.getType().equals("StringParameterValue")) {
	                    value = ((StringParameterValue)definition.getDefaultParameterValue()).value;
	                }
	                this.parameterValues.add(new StringParameterValue(name, value));
	            }
	        }
	    }
	}

	public String getProjectName() {
		return projectName;
	}

    public Item getJob() {
        return Hudson.getInstance().getItemByFullName(getProjectName(), Item.class);
    }

	public List<Builder> getProjectBuilders() {
		AbstractProject p = (AbstractProject) Hudson.getInstance().getItem(projectName);
		if (p instanceof Project) return ((Project)p).getBuilders();
		else if (p instanceof MatrixProject) return ((MatrixProject)p).getBuilders();
                else return Collections.emptyList();
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@Override
		public String getDisplayName() {
			return "Use builders from another project";
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		/**
		 * Form validation method.
		 */
		public FormValidation doCheckProjectName(@AncestorInPath AccessControlled anc, @QueryParameter String value) {
			// Require CONFIGURE permission on this project
			if (!anc.hasPermission(Item.CONFIGURE)) return FormValidation.ok();
			Item item = Hudson.getInstance().getItemByFullName(
					value, Item.class);
			if (item == null) {
				return FormValidation.error(Messages.BuildTrigger_NoSuchProject(value,
						AbstractProject.findNearest(value)
								.getName()));
			}
			if (!(item instanceof Project) && !(item instanceof MatrixProject)) {
				return FormValidation.error(Messages.BuildTrigger_NotBuildable(value));
			}
			return FormValidation.ok();
		}
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		for (Builder builder: getProjectBuilders()) {
			if (!builder.perform(build, launcher, listener)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
		for (Builder builder: getProjectBuilders()) {
			if (!builder.prebuild(build, listener)) {
				return false;
			}
		}
		return true;
	}

}
