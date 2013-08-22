package brooklyn.entity.database.postgresql;

import static brooklyn.util.ssh.BashCommands.*;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.ResourceUtils;
import brooklyn.util.collections.MutableList;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.net.Urls;
import brooklyn.util.task.DynamicTasks;
import brooklyn.util.task.ssh.SshTasks;
import brooklyn.util.text.StringFunctions;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * The SSH implementation of the {@link PostgreSqlDriver}.
 */

public class PostgreSqlSshDriver extends AbstractSoftwareProcessSshDriver
        implements PostgreSqlDriver {

    public static final Logger log = LoggerFactory
            .getLogger(PostgreSqlSshDriver.class);


    private final Iterable<String> pgctlLocations = ImmutableList.of(
            "/usr/lib/postgresql/9.*/bin/",
            "/usr/lib/postgresql/8.*/bin/",
            "/opt/local/lib/postgresql9*/bin/",
            "/opt/local/lib/postgresql8*/bin/",
            "/usr/local/bin/",
            "/usr/bin/",
            "/bin/"
    );

    public PostgreSqlSshDriver(PostgreSqlNodeImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public void install() {
        DynamicTasks.queueIfPossible(SshTasks.dontRequireTtyForSudo(getMachine(), true)).orSubmitAndBlock();
        
        // Check whether we can find a usable pg_ctl, and if not install one
        MutableList<String> findOrInstall = MutableList.<String>of()
                .append("which pg_ctl")
                .appendAll(Iterables.transform(pgctlLocations, StringFunctions.formatter("test -x %s/pg_ctl")))
                .append(installPackage(ImmutableMap.of(
                        "yum", "postgresql postgresql-server", 
                        "apt", "postgresql", 
                        "port", "postgresql91 postgresql91-server"
                    ), "postgresql"))
                // due to impl of installPackage, it will not come to the line below I don't think
                .append(warn("WARNING: failed to find or install postgresql binaries (will likely fail later unless binaries found in path)"));
        
        // Link to correct binaries folder (different versions of pg_ctl and psql don't always play well together)
        MutableList<String> linkFromHere = MutableList.<String>of()
                .append(ifExecutableElse1("pg_ctl", chainGroup("PG_EXECUTABLE=`which pg_ctl`", "PG_DIR=`dirname $PG_EXECUTABLE`",
                        "echo 'found pg_ctl in '$PG_DIR' on path so linking PG bin/ to that dir'", "ln -s $PG_DIR bin")))
                .appendAll(Iterables.transform(pgctlLocations, givenDirIfFileExistsInItLinkToDir("pg_ctl", "bin")))
                .append(fail("WARNING: failed to find postgresql binaries for pg_ctl; aborting", 9));

        newScript(INSTALLING).body.append(
                dontRequireTtyForSudo(),
                alternativesGroup(findOrInstall),
                alternativesGroup(linkFromHere))
                .failOnNonZeroResultCode().queue();
    }

    private static Function<String, String> givenDirIfFileExistsInItLinkToDir(final String filename, final String linkToMake) {
        return new Function<String, String>() {
            public String apply(@Nullable String dir) {
                return ifExecutableElse1(Urls.mergePaths(dir, filename), 
                        chainGroup("echo 'found "+filename+" in "+dir+" so linking to it in "+linkToMake+"'", "ln -s " + dir + " "+linkToMake));
            }
        };
    }

    public static String sudoAsUserAppendCommandOutputToFile(String user, String commandWhoseOutputToWrite, String file) {
        return executeCommandThenAsUserTeeOutputToFile(commandWhoseOutputToWrite, user, file);
    }
    
    @Override
    public void customize() {
        // Some OSes start postgres during package installation
        newScript(CUSTOMIZING).body.append(sudoAsUser("postgres", "/etc/init.d/postgresql stop")).queue();
        newScript(CUSTOMIZING).body.append(
                sudo("mkdir -p " + getDataDir()),
                sudo("chown postgres:postgres " + getDataDir()),
                sudo("chmod 700 " + getDataDir()),
                sudo("touch " + getLogFile()),
                sudo("chown postgres:postgres " + getLogFile()),
                sudoAsUser("postgres", getInstallDir() + "/bin/initdb -D " + getDataDir()),
                sudoAsUserAppendCommandOutputToFile("postgres", "echo \"listen_addresses = '*'\"", getDataDir() + "/postgresql.conf"),
                sudoAsUserAppendCommandOutputToFile("postgres", "echo \"port = " + getEntity().getAttribute(PostgreSqlNode.POSTGRESQL_PORT) +  "\"", getDataDir() + "/postgresql.conf"),
                // TODO give users control which hosts can connect and the authentication mechanism
                sudoAsUserAppendCommandOutputToFile("postgres", "echo \"host    all         all         0.0.0.0/0             md5\"", getDataDir() + "/pg_hba.conf")
        ).failOnNonZeroResultCode().execute();

        // execute above (to wait for commands to complete before customizing)
        
        customizeUserCreationScript();

        /*
        * Try establishing an external connection. If you get a "Connection refused...accepting TCP/IP connections on port 5432?" error then the port is probably closed. 
        * Check that the firewall allows external TCP/IP connections (netstat -nap). You can open a port with lokkit or by configuring the iptables.
        */
    }

    protected void customizeUserCreationScript() {
        log.info("Copying creation script " + getEntity().toString());
        String creationScriptUrl = entity.getConfig(PostgreSqlNode.CREATION_SCRIPT_URL);
        Reader creationScript;
        if (creationScriptUrl != null)
            creationScript = new InputStreamReader(new ResourceUtils(entity).getResourceFromUrl(creationScriptUrl));
        else creationScript = new StringReader(entity.getConfig(PostgreSqlNode.CREATION_SCRIPT_CONTENTS));

        getMachine().copyTo(creationScript, getRunDir() + "/creation-script.sql");

        newScript(CUSTOMIZING).body.append(callPgctl("start", true),
                sudoAsUser("postgres", getInstallDir() + "/bin/psql -p " + entity.getAttribute(PostgreSqlNode.POSTGRESQL_PORT) + " --file " + getRunDir() + "/creation-script.sql"),
                callPgctl("stop", true)).
                failOnNonZeroResultCode().execute();
    }

    protected String getDataDir() {
        return getRunDir() + "/data";
    }

    protected String getLogFile() {
        return getRunDir() + "/postgresql.log";
    }


    protected String callPgctl(String command, boolean waitForIt) {
        return sudoAsUser("postgres", getInstallDir() + "/bin/pg_ctl -D " + getDataDir()
                + " -l " + getLogFile() + (waitForIt ? " -w " : " ") + command);
    }

    @Override
    public void launch() {
        log.info(String.format("Starting entity %s at %s", this, getLocation()));
        newScript(MutableMap.of("usePidFile", true), LAUNCHING).
                updateTaskAndFailOnNonZeroResultCode().body.append(callPgctl("start", false)).execute();
    }

    @Override
    public boolean isRunning() {
        return newScript(MutableMap.of("usePidFile", false), CHECK_RUNNING)
                .body.append(getStatusCmd())
                .execute() == 0;
    }

    @Override
    public void stop() {
        newScript(MutableMap.of("usePidFile", false), STOPPING).body.append(callPgctl("stop", false)).failOnNonZeroResultCode().execute();
    }

    @Override
    public void kill() {
        newScript(MutableMap.of("usePidFile", true), KILLING).execute();
    }

    @Override
    public PostgreSqlNodeImpl getEntity() {
        return (PostgreSqlNodeImpl) super.getEntity();
    }

    @Override
    public String getStatusCmd() {
        return callPgctl("status", false);
    }
}