package hudson.plugins.templateproject;

import hudson.Extension;
import hudson.Launcher;
import hudson.matrix.MatrixProject;
import hudson.model.*;
import hudson.security.AccessControlled;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildWrapper;
import hudson.tasks.Builder;
import hudson.tasks.Messages;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.*;

import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class ProxyBuilder extends Builder {

    private final static Logger LOGGER = Logger.getLogger(ProxyBuilder.class.getName());

	private final String projectName;
    private List<ParameterValue> parameterValues;

    public final static class MyStringValue {
        public String name;
        public String value;
        @DataBoundConstructor
        public MyStringValue(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

	@DataBoundConstructor
	public ProxyBuilder(String projectName, List<ParameterValue> parameterValues) {
	    this.projectName = projectName;
        this.parameterValues = parameterValues;
        log("Constructor called: " + projectName + ", " + nullSafe(parameterValues));
	}

    public static void log(String s) {
        LOGGER.log(Level.ALL, s);
        System.out.println("LEVON: " + s);
    }

    public String nullSafe(Object o) {
        return (o != null) ? o.toString() : "null";
    }

    public String someField;

    public boolean isParameterized() {
        return true;//getProject().isParameterized();
    }

    public List<ParameterDefinition> getParameterDefinitions() {
        List<ParameterDefinition> parameterDefinitions = getParameterDefinitions(projectName);
        for (ParameterDefinition p : parameterDefinitions) {
            ParameterValue value = getParameterValue(parameterValues, p.getName());
            if (value != null) {
                log("Parameter " + p.getName() + " = " + value);
            }
        }
        return parameterDefinitions;
    }

    public static List<ParameterDefinition> getParameterDefinitions(String projectName) {
        Project p = getProject(projectName);
        if (p != null) {
            ParametersDefinitionProperty property = (ParametersDefinitionProperty)p.getProperty(ParametersDefinitionProperty.class);
            return property.getParameterDefinitions();
        } else {
            return Collections.emptyList();
        }
    }

    public Project getProject() {
        return getProject(projectName);
    }

    public static Project getProject(String projectName) {
        return (Project) Hudson.getInstance().getItem(projectName);
    }

    public List<ParameterValue> getParameterValues() {
        return parameterValues;
    }

    public static ParameterValue getParameterValue(List<ParameterValue> parameterValues, String name) {
        for (ParameterValue v : parameterValues) {
            if (v.getName().equals(name)) {
                return v;
            }
        }
        return null;
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

    public Map<Descriptor<BuildWrapper>, BuildWrapper> getProjectBuilderWrappers() {
        AbstractProject p = (AbstractProject) Hudson.getInstance().getItem(projectName);
        if (p instanceof Project) return ((Project)p).getBuildWrappers();
        else if (p instanceof MatrixProject) return ((MatrixProject)p).getBuildWrappers();
        else return Collections.emptyMap();
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

        @Override
        public Builder newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            log("newInstance called: " + req + "\n" + formData);
            String projectName = formData.getString("projectName");
            List<ParameterValue> parameterValues = buildParameters(req, formData);
            return new ProxyBuilder(projectName, parameterValues);
        }

        public ParameterDefinition getParameterDefinition(List<ParameterDefinition> definitions, String name) {
            for (ParameterDefinition p : definitions) {
                if (p.getName().equals(name)) {
                    return p;
                }
            }
            return null;
        }

        public List<ParameterValue> buildParameters(StaplerRequest req, JSONObject formData) {
            List<ParameterValue> values = new ArrayList<ParameterValue>();

            //JSONObject formData = req.getSubmittedForm();
            JSONArray a = JSONArray.fromObject(formData.get("parameter"));
            String projectName = formData.getString("projectName");
            if (a != null) {
                List<ParameterDefinition> parameterDefinitions = getParameterDefinitions(projectName);
                for (Object o : a) {
                    JSONObject jo = (JSONObject) o;
                    String name = jo.getString("name");

                    ParameterDefinition d = getParameterDefinition(parameterDefinitions, name);
                    if(d==null)
                        throw new IllegalArgumentException("No such parameter definition: " + name);
                    ParameterValue parameterValue = d.createValue(req, jo);
                    values.add(parameterValue);
                }
                return values;
            } else {
                return Collections.emptyList();
            }
        }
    }

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
        Project project = getProject();
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
            build.addAction(new ParametersAction(parameterValues));
			if (!builder.prebuild(build, listener)) {
				return false;
			}
		}
		return true;
	}

}
