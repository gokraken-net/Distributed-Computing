/*
 * This file is part of BOINC.
 * http://boinc.berkeley.edu
 * Copyright (C) 2012 University of California
 *
 * BOINC is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * BOINC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with BOINC.  If not, see <http://www.gnu.org/licenses/>.
 */

package edu.berkeley.boinc.rpc;

import android.util.Log;
import android.util.Xml;

import org.apache.commons.lang3.StringUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import edu.berkeley.boinc.utils.Logging;

public class AcctMgrInfoParser extends BaseParser {
    static final String ACCT_MGR_INFO_TAG = "acct_mgr_info";

    private AcctMgrInfo mAcctMgrInfo = null;

    AcctMgrInfo getAccountMgrInfo() {
        return mAcctMgrInfo;
    }

    public static AcctMgrInfo parse(String rpcResult) {
        try {
            AcctMgrInfoParser parser = new AcctMgrInfoParser();
            Xml.parse(rpcResult, parser);
            return parser.getAccountMgrInfo();
        }
        catch(SAXException e) {
            if(Logging.WARNING) {
                Log.w(Logging.TAG, "AcctMgrRPCReplyParser: malformated XML");
            }
            return null;
        }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        super.startElement(uri, localName, qName, attributes);
        if(localName.equalsIgnoreCase(ACCT_MGR_INFO_TAG)) {
            mAcctMgrInfo = new AcctMgrInfo();
        }
        else {
            mElementStarted = true;
            mCurrentElement.setLength(0);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        super.endElement(uri, localName, qName);
        try {
            if(mAcctMgrInfo != null) {
                // inside <acct_mgr_info>
                if(localName.equalsIgnoreCase(ACCT_MGR_INFO_TAG)) {
                    // closing tag
                    if(!StringUtils.isAllEmpty(mAcctMgrInfo.getAcctMgrName(),
                                              mAcctMgrInfo.getAcctMgrUrl()) &&
                       mAcctMgrInfo.isHavingCredentials()) {
                        mAcctMgrInfo.setPresent(true);
                    }
                }
                else {
                    // decode inner tags
                    if(localName.equalsIgnoreCase(AcctMgrInfo.Fields.ACCT_MGR_NAME)) {
                        mAcctMgrInfo.setAcctMgrName(mCurrentElement.toString());
                    }
                    else if(localName.equalsIgnoreCase(AcctMgrInfo.Fields.ACCT_MGR_URL)) {
                        mAcctMgrInfo.setAcctMgrUrl(mCurrentElement.toString());
                    }
                    else if(localName.equalsIgnoreCase(AcctMgrInfo.Fields.HAVING_CREDENTIALS)) {
                        mAcctMgrInfo.setHavingCredentials(true);
                    }
                    else if(localName.equalsIgnoreCase(AcctMgrInfo.Fields.COOKIE_REQUIRED)) {
                        mAcctMgrInfo.setCookieRequired(true);
                    }
                    else if(localName.equalsIgnoreCase(AcctMgrInfo.Fields.COOKIE_FAILURE_URL)) {
                        mAcctMgrInfo.setCookieFailureUrl(mCurrentElement.toString());
                    }
                }
            }
        }
        catch(Exception e) {
            if(Logging.ERROR) {
                Log.e(Logging.TAG, "AcctMgrInfoParser.endElement error: ", e);
            }
        }
        mElementStarted = false;
    }
}
