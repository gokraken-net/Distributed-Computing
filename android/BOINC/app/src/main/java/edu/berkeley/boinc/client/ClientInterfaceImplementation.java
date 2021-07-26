package edu.berkeley.boinc.client;

import android.util.Log;
import edu.berkeley.boinc.rpc.AccountIn;
import edu.berkeley.boinc.rpc.AccountManager;
import edu.berkeley.boinc.rpc.AccountOut;
import edu.berkeley.boinc.rpc.AcctMgrRPCReply;
import edu.berkeley.boinc.rpc.GlobalPreferences;
import edu.berkeley.boinc.rpc.Message;
import edu.berkeley.boinc.rpc.Project;
import edu.berkeley.boinc.rpc.ProjectAttachReply;
import edu.berkeley.boinc.rpc.ProjectConfig;
import edu.berkeley.boinc.rpc.ProjectInfo;
import edu.berkeley.boinc.rpc.RpcClient;
import edu.berkeley.boinc.rpc.Transfer;
import edu.berkeley.boinc.utils.BOINCErrors;
import edu.berkeley.boinc.utils.Logging;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class implements RPC commands with the client.
 * Extends RpcClient with polling, re-try and other mechanisms
 * Most functions can block executing thread, do not call them from UI thread!
 */
@Singleton
public class ClientInterfaceImplementation extends RpcClient {
    // interval between polling retries in ms
    private final int minRetryInterval = 1000;
    private ClientStatus clientStatus;

    @Inject
    public ClientInterfaceImplementation(ClientStatus clientStatus) {
        this.clientStatus = clientStatus;
    }

    /**
     * Reads authentication key from specified file path and authenticates GUI for advanced RPCs with the client
     *
     * @param authFilePath absolute path to file containing gui authentication key
     * @return success
     */
    public Boolean authorizeGuiFromFile(String authFilePath) {
        String authToken = readAuthToken(authFilePath);
        return authorize(authToken);
    }

    /**
     * Sets run mode of BOINC client
     *
     * @param mode see class BOINCDefs
     * @return success
     */
    public Boolean setRunMode(Integer mode) {
        return setRunMode(mode, 0);
    }

    /**
     * Sets network mode of BOINC client
     *
     * @param mode see class BOINCDefs
     * @return success
     */
    public Boolean setNetworkMode(Integer mode) {
        return setNetworkMode(mode, 0);
    }

    /**
     * Writes the given GlobalPreferences via RPC to the client. After writing, the active preferences are read back and written to ClientStatus.
     *
     * @param prefs new target preferences for the client
     * @return success
     */
    public boolean setGlobalPreferences(GlobalPreferences prefs) {
        boolean retval1 = setGlobalPrefsOverrideStruct(prefs); //set new override settings
        boolean retval2 = readGlobalPrefsOverride(); //trigger reload of override settings
        if (!retval1 || !retval2) {
            return false;
        }
        GlobalPreferences workingPrefs = getGlobalPrefsWorkingStruct();
        if (workingPrefs != null) {
            clientStatus.setPrefs(workingPrefs);
            return true;
        }
        return false;
    }

    /**
     * Reads authentication token for GUI RPC authentication from file
     *
     * @param authFilePath absolute path to file containing GUI RPC authentication
     * @return GUI RPC authentication code
     */
    String readAuthToken(String authFilePath) {
        String authKey = "";
        try (BufferedReader br = new BufferedReader(new FileReader(new File(authFilePath)))) {
            authKey = br.readLine();
        } catch (FileNotFoundException fnfe) {
            Log.e(Logging.TAG, "Auth file not found: ", fnfe);
        } catch (IOException ioe) {
            Log.e(Logging.TAG, "IOException: ", ioe);
        }
        int authKeyLength = authKey == null ? 0 : authKey.length();
        Log.d(Logging.TAG, "Authentication key acquired. length: " + authKeyLength);

        return authKey;
    }

    /**
     * Reads project configuration for specified master URL.
     *
     * @param url master URL of the project
     * @return project configuration information
     */
    public ProjectConfig getProjectConfigPolling(String url) {
        ProjectConfig config = null;

        Boolean success = getProjectConfig(url); //asynchronous call
        if (success) { //only continue if attach command did not fail
            // verify success of getProjectConfig with poll function
            Boolean loop = true;
            while (loop) {
                loop = false;
                try {
                    Thread.sleep(minRetryInterval);
                } catch (Exception ignored) {
                }
                config = getProjectConfigPoll();
                if (config == null) {
                    Log.e(Logging.TAG, "ClientInterfaceImplementation.getProjectConfigPolling: returned null.");

                    return null;
                }
                if (config.getErrorNum() == BOINCErrors.ERR_IN_PROGRESS) {
                    loop = true; //no result yet, keep looping
                } else {
                    //final result ready
                    if (config.getErrorNum() == 0) {
                        Log.d(Logging.TAG,
                                "ClientInterfaceImplementation.getProjectConfigPolling: ProjectConfig retrieved: " +
                                config.getName());
                    } else {
                        Log.d(Logging.TAG,
                                "ClientInterfaceImplementation.getProjectConfigPolling: final result with error_num: " +
                                config.getErrorNum());
                    }
                }
            }
        }
        return config;
    }

    /**
     * Attaches project, requires authenticator
     *
     * @param url           URL of project to be attached, either masterUrl(HTTP) or webRpcUrlBase(HTTPS)
     * @param projectName   name of project as shown in the manager
     * @param authenticator user authentication key, has to be obtained first
     * @return success
     */

    public Boolean attachProject(String url, String projectName, String authenticator) {
        Boolean success = projectAttach(url, authenticator, projectName); //asynchronous call to attach project
        if (success) {
            // verify success of projectAttach with poll function
            ProjectAttachReply reply = projectAttachPoll();
            while (reply != null && reply.getErrorNum() ==
                                    BOINCErrors.ERR_IN_PROGRESS) { // loop as long as reply.error_num == BOINCErrors.ERR_IN_PROGRESS
                try {
                    Thread.sleep(minRetryInterval);
                } catch (Exception ignored) {
                }
                reply = projectAttachPoll();
            }
            return (reply != null && reply.getErrorNum() == BOINCErrors.ERR_OK);
        } else {
            Log.d(Logging.TAG, "rpc.projectAttach failed.");
        }
        return false;
    }

    /**
     * Checks whether project of given master URL is currently attached to BOINC client
     *
     * @param url master URL of the project
     * @return true if attached
     */

    public Boolean checkProjectAttached(String url) {
        try {
            List<Project> attachedProjects = getProjectStatus();
            for (Project project : attachedProjects) {
                Log.d(Logging.TAG, project.getMasterURL() + " vs " + url);

                if (project.getMasterURL().equals(url)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(Logging.TAG, "ClientInterfaceImplementation.checkProjectAttached() error: ", e);
        }
        return false;
    }

    /**
     * Looks up account credentials for given user data.
     * Contains authentication key for project attachment.
     *
     * @param credentials account credentials
     * @return account credentials
     */

    public AccountOut lookupCredentials(AccountIn credentials) {
        AccountOut auth = null;
        Boolean success = lookupAccount(credentials); //asynch
        if (success) {
            // get authentication token from lookupAccountPoll
            Boolean loop = true;
            while (loop) {
                loop = false;
                try {
                    Thread.sleep(minRetryInterval);
                } catch (Exception ignored) {
                }
                auth = lookupAccountPoll();
                if (auth == null) {
                    Log.e(Logging.TAG, "ClientInterfaceImplementation.lookupCredentials: returned null.");

                    return null;
                }
                if (auth.getErrorNum() == BOINCErrors.ERR_IN_PROGRESS) {
                    loop = true; //no result yet, keep looping
                } else {
                    //final result ready
                    if (auth.getErrorNum() == 0) {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.lookupCredentials: authenticator retrieved.");
                    } else {
                        Log.d(Logging.TAG,
                                "ClientInterfaceImplementation.lookupCredentials: final result with error_num: " +
                                auth.getErrorNum());
                    }
                }
            }
        } else {
            Log.d(Logging.TAG, "rpc.lookupAccount failed.");
        }
        return auth;
    }

    /**
     * Runs transferOp for a list of given transfers.
     * E.g. batch pausing of transfers
     *
     * @param transfers list of transfered operation gets executed for
     * @param operation see BOINCDefs
     * @return success
     */

    boolean transferOperation(List<Transfer> transfers, int operation) {
        boolean success = true;
        for (Transfer transfer : transfers) {
            success = success && transferOp(operation, transfer.getProjectUrl(), transfer.getName());

            Log.d(Logging.TAG, "transfer: " + transfer.getName() + " " + success);
        }
        return success;
    }

    /**
     * Creates account for given user information and returns account credentials if successful.
     *
     * @param information account credentials
     * @return account credentials (see status inside, to check success)
     */

    public AccountOut createAccountPolling(AccountIn information) {
        AccountOut auth = null;

        Boolean success = createAccount(information); //asynchronous call to attach project
        if (success) {
            Boolean loop = true;
            while (loop) {
                loop = false;
                try {
                    Thread.sleep(minRetryInterval);
                } catch (Exception ignored) {
                }
                auth = createAccountPoll();
                if (auth == null) {
                    Log.e(Logging.TAG, "ClientInterfaceImplementation.createAccountPolling: returned null.");

                    return null;
                }
                if (auth.getErrorNum() == BOINCErrors.ERR_IN_PROGRESS) {
                    loop = true; //no result yet, keep looping
                } else {
                    //final result ready
                    if (auth.getErrorNum() == 0) {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.createAccountPolling: authenticator retrieved.");
                    } else {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.createAccountPolling: final result with error_num: "
                                + auth.getErrorNum());
                    }
                }
            }
        } else {
            Log.d(Logging.TAG, "rpc.createAccount returned false.");
        }
        return auth;
    }

    /**
     * Adds account manager to BOINC client.
     * There can only be a single acccount manager be active at a time.
     *
     * @param url      URL of account manager
     * @param userName user name
     * @param pwd      password
     * @return status of attachment
     */

    public AcctMgrRPCReply addAcctMgr(String url, String userName, String pwd) {
        AcctMgrRPCReply reply = null;
        Boolean success = acctMgrRPC(url, userName, pwd);
        if (success) {
            Boolean loop = true;
            while (loop) {
                reply = acctMgrRPCPoll();
                if (reply == null || reply.getErrorNum() != BOINCErrors.ERR_IN_PROGRESS) {
                    loop = false;
                    //final result ready
                    if (reply == null) {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.addAcctMgr: failed, reply null.");
                    } else {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.addAcctMgr: returned " + 
                                reply.getErrorNum());
                    }
                } else {
                    try {
                        Thread.sleep(minRetryInterval);
                    } catch (Exception ignored) {
                    }
                }
            }
        } else {
            Log.d(Logging.TAG, "rpc.acctMgrRPC returned false.");
        }
        return reply;
    }


    /**
     * Synchronized BOINC client projects with information of account manager.
     * Sequence copied from BOINC's desktop manager.
     *
     * @param url URL of account manager
     * @return success
     */
    boolean synchronizeAcctMgr(String url) {
        // 1st get_project_config for account manager url
        boolean success = getProjectConfig(url);
        ProjectConfig reply;
        if (success) {
            boolean loop = true;
            while (loop) {
                loop = false;
                try {
                    Thread.sleep(minRetryInterval);
                } catch (Exception ignored) {
                }
                reply = getProjectConfigPoll();
                if (reply == null) {
                    Log.e(Logging.TAG, "ClientInterfaceImplementation.synchronizeAcctMgr: getProjectConfigreturned null.");

                    return false;
                }
                if (reply.getErrorNum() == BOINCErrors.ERR_IN_PROGRESS) {
                    loop = true; //no result yet, keep looping
                } else {
                    //final result ready
                    if (reply.getErrorNum() == 0) {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.synchronizeAcctMgr: project config retrieved.");
                    } else {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.synchronize" +
                                "AcctMgr: final result with error_num: " + reply.getErrorNum());
                    }
                }
            }
        } else {
            Log.d(Logging.TAG, "rpc.getProjectConfig returned false.");
        }

        // 2nd acct_mgr_rpc with <use_config_file/>
        AcctMgrRPCReply reply2;
        success = acctMgrRPC(); //asynchronous call to synchronize account manager
        if (success) {
            boolean loop = true;
            while (loop) {
                loop = false;
                try {
                    Thread.sleep(minRetryInterval);
                } catch (Exception ignored) {
                }
                reply2 = acctMgrRPCPoll();
                if (reply2 == null) {
                    Log.e(Logging.TAG, "ClientInterfaceImplementation.synchronizeAcctMgr: acctMgrRPCPoll returned null.");

                    return false;
                }
                if (reply2.getErrorNum() == BOINCErrors.ERR_IN_PROGRESS) {
                    loop = true; //no result yet, keep looping
                } else {
                    //final result ready
                    if (reply2.getErrorNum() == 0) {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.synchronizeAcctMgr: acct mngr reply retrieved.");
                    } else {
                        Log.d(Logging.TAG, "ClientInterfaceImplementation.synchronizeAcctMgr: final result with error_num: " + reply2.getErrorNum());
                    }
                }
            }
        } else {
            Log.d(Logging.TAG, "rpc.acctMgrRPC returned false.");
        }

        return true;
    }

    @Override
    public boolean setCcConfig(String ccConfig) {
        // set CC config and trigger re-read.
        super.setCcConfig(ccConfig);
        return super.readCcConfig();
    }

    /**
     * Returns List of event log messages
     *
     * @param seqNo  lower bound of sequence number
     * @param number number of messages returned max, can be less
     * @return list of messages
     */

    // returns given number of client messages, older than provided seqNo
    // if seqNo <= 0 initial data retrieval
    List<Message> getEventLogMessages(int seqNo, int number) {
        // determine oldest message seqNo for data retrieval
        int lowerBound;
        if (seqNo > 0)
            lowerBound = seqNo - number - 2;
        else
            lowerBound = getMessageCount() - number - 1; // can result in >number results, if client writes message btwn. here and rpc.getMessages!

        // less than desired number of messsages available, adapt lower bound
        if (lowerBound < 0)
            lowerBound = 0;
        List<Message> msgs = getMessages(lowerBound); // returns ever messages with seqNo > lowerBound

        if (seqNo > 0) {
            // remove messages that are >= seqNo
            msgs.removeIf(message -> message.getSeqno() >= seqNo);
        }

        if(!msgs.isEmpty()) {
            Log.d(Logging.TAG, "getEventLogMessages: returning array with " + msgs.size()
                               + " entries. for lowerBound: " + lowerBound + " at 0: "
                               + msgs.get(0).getSeqno() + " at " + (msgs.size() - 1) + ": "
                               + msgs.get(msgs.size() - 1).getSeqno());
        }
        return msgs;
    }

    /**
     * Returns list of projects from all_projects_list.xml that...
     * - support Android
     * - support CPU architecture
     * - are not yet attached
     *
     * @return list of attachable projects
     */
    List<ProjectInfo> getAttachableProjects(String boincPlatformName, String boincAltPlatformName) {
        Log.d(Logging.TAG, "getAttachableProjects for platform: " + boincPlatformName + " or " + boincAltPlatformName);

        List<ProjectInfo> allProjectsList = getAllProjectsList(); // all_projects_list.xml
        List<Project> attachedProjects = getState().getProjects(); // currently attached projects

        List<ProjectInfo> attachableProjects = new ArrayList<>(); // array to be filled and returned

        if (allProjectsList == null)
            return Collections.emptyList();

        //filter projects that do not support Android
        for (ProjectInfo candidate : allProjectsList) {
            // check whether already attached
            boolean alreadyAttached = false;
            for (Project attachedProject : attachedProjects) {
                if (attachedProject.getMasterURL().equals(candidate.getUrl())) {
                    alreadyAttached = true;
                    break;
                }
            }
            if (alreadyAttached)
                continue;

            // project is not yet attached, check whether it supports CPU architecture
            for (String supportedPlatform : candidate.getPlatforms()) {
                if (supportedPlatform.contains(boincPlatformName) ||
                   (!boincAltPlatformName.isEmpty() && supportedPlatform.contains(boincAltPlatformName))) {
                    // project is not yet attached and does support platform
                    // add to list, if not already in it
                    if (!attachableProjects.contains(candidate))
                        attachableProjects.add(candidate);
                    break;
                }
            }
        }

        Log.d(Logging.TAG, "getAttachableProjects: number of candidates found: "+ 
                attachableProjects.size());

        return attachableProjects;
    }

    /**
     * Returns list of account managers from all_projects_list.xml
     *
     * @return list of account managers
     */
    List<AccountManager> getAccountManagers() {
        List<AccountManager> accountManagers = getAccountManagersList(); // from all_projects_list.xml

        Log.d(Logging.TAG, "getAccountManagers: number of account managers found: " + accountManagers.size());

        return accountManagers;
    }

    ProjectInfo getProjectInfo(String url) {
        List<ProjectInfo> allProjectsList = getAllProjectsList(); // all_projects_list.xml
        for (ProjectInfo tmp : allProjectsList) {
            if (tmp.getUrl().equals(url))
                return tmp;
        }

        Log.e(Logging.TAG, "getProjectInfo: could not find info for: " + url);

        return null;
    }

    boolean setDomainName(String deviceName) {
        boolean success = setDomainNameRpc(deviceName);

        Log.d(Logging.TAG, "setDomainName: success " + success);
        
        return success;
    }

    /**
     * Establishes socket connection to BOINC client.
     * Requirement for information exchange via RPC
     * @return success
     */
    public Boolean connect() {
        return open("localhost", 31416);
    }
}
