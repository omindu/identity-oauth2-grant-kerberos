/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.oauth2.grant.kerberos;

import org.apache.axiom.om.util.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.ResponseHeader;
import org.wso2.carbon.identity.oauth2.model.RequestParameter;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.token.handlers.grant.AbstractAuthorizationGrantHandler;
import sun.security.jgss.GSSHeader;
import sun.security.jgss.GSSUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

/**
 * Kerberos OAuth2 grant type for Identity Server
 */
public class KerberosGrant extends AbstractAuthorizationGrantHandler {

    // Hardcoded for initial testing
    public static final String KERBEROS_SPN = "HTTP/idp.example.com@EXAMPLE.COM";
    public static final String KERBEROS_PASSWORD = "Xyz12345";
    private static Log log = LogFactory.getLog(KerberosGrant.class);
    private static GSSManager gssManager = GSSManager.getInstance();

    @Override
    public boolean validateGrant(OAuthTokenReqMessageContext oAuthTokenReqMessageContext)
            throws IdentityOAuth2Exception {

        String kerberosUsersId = null;

        // extract request parameters
        RequestParameter[] parameters = oAuthTokenReqMessageContext.getOauth2AccessTokenReqDTO().getRequestParameters();

        String kerberosServiceToken = null;

        for (RequestParameter parameter : parameters) {
            if (KerberosGrantConstants.KERBEROS_GRANT_TOKEN.equals(parameter.getKey())) {
                if (parameter.getValue() != null && parameter.getValue().length > 0) {
                    kerberosServiceToken = parameter.getValue()[0];
                }
            }
        }

        Oid oidOfToken = GSSUtil.GSS_SPNEGO_MECH_OID;
        try {
            oidOfToken = getOid(Base64.decode(kerberosServiceToken));
        } catch (IOException | GSSException e) {
            log.warn("Unable to get Oid. Setting to default type SPENGO" + e.getMessage());
        }

        GSSCredential gssCredential = null;
        try {
            gssCredential = createCredentials(KERBEROS_SPN, KERBEROS_PASSWORD.toCharArray(), oidOfToken);
        } catch (LoginException | PrivilegedActionException e) {
            log.error(e);
        }

        if (gssCredential != null) {
            try {
                kerberosUsersId = validateKerberosTicket(gssCredential, Base64.decode(kerberosServiceToken));
                if (log.isDebugEnabled()) {
                    log.debug("Kerberos token validated successfully");
                }
            } catch (GSSException e) {
                log.error(e);
            }
        }

        if (kerberosUsersId != null) {
            // if valid set authorized kerberos user Id as grant user
            AuthenticatedUser kerberosUser = new AuthenticatedUser();
            kerberosUser.setUserName(kerberosUsersId);
            oAuthTokenReqMessageContext.setAuthorizedUser(kerberosUser);
            oAuthTokenReqMessageContext.setScope(oAuthTokenReqMessageContext.getOauth2AccessTokenReqDTO().getScope());
        } else {
            ResponseHeader responseHeader = new ResponseHeader();
            responseHeader.setKey("SampleHeader-999");
            responseHeader.setValue("Provided Kerberos token is Invalid.");
            oAuthTokenReqMessageContext.addProperty("RESPONSE_HEADERS", new ResponseHeader[] { responseHeader });
        }

        if (log.isDebugEnabled()) {
            log.debug("Issuing OAuth2 token by kerberos-oauth2 grant");
        }
        // if the ticket validation failed the kerberosUserId will be null, therefore following will return false
        return (kerberosUsersId != null);
    }

    /**
     * Create credentials object using provided username and password
     *
     * @param spnUsername
     * @param spnPassword
     * @return
     * @throws LoginException
     * @throws PrivilegedActionException
     */
    private GSSCredential createCredentials(String spnUsername, char[] spnPassword, final Oid oid)
            throws LoginException, PrivilegedActionException {
        CallbackHandler callbackHandler = getUserNamePasswordCallbackHandler(spnUsername, spnPassword);

        LoginContext loginContext = new LoginContext(KerberosGrantConstants.SERVER, callbackHandler);
        loginContext.login();

        if (log.isDebugEnabled()) {
            log.debug("Pre-authentication successful for with Kerberos Server.");
        }

        final PrivilegedExceptionAction<GSSCredential> action = new PrivilegedExceptionAction<GSSCredential>() {
            public GSSCredential run() throws GSSException {
                return gssManager.createCredential(null, GSSCredential.INDEFINITE_LIFETIME, oid,
                        GSSCredential.ACCEPT_ONLY);
            }
        };

        if (log.isDebugEnabled()) {
            Set<Principal> principals = loginContext.getSubject().getPrincipals();
            String principalName = null;
            if (principals != null) {
                principalName = principals.toString();
            }
            log.debug("Creating gss credentials as principal : " + principalName);
        }
        return Subject.doAs(loginContext.getSubject(), action);
    }

    /**
     * @param username
     * @param password
     * @return
     */
    private CallbackHandler getUserNamePasswordCallbackHandler(final String username, final char[] password) {

        return new CallbackHandler() {
            public void handle(final Callback[] callback) {
                for (Callback currentCallBack : callback) {
                    if (currentCallBack instanceof NameCallback) {
                        final NameCallback nameCallback = (NameCallback) currentCallBack;
                        nameCallback.setName(username);
                    } else if (currentCallBack instanceof PasswordCallback) {
                        final PasswordCallback passCallback = (PasswordCallback) currentCallBack;
                        passCallback.setPassword(password);
                    } else {
                        log.error("Unsupported Callback class = " + currentCallBack.getClass().getName());
                    }
                }
            }
        };
    }

    /**
     * Decrypts the provided Kerberos token using generated credentials and validate it
     *
     * @param gssCredentials
     * @param gssToken
     * @return the name of the user, if an error occurred return null
     * @throws GSSException
     */
    private String validateKerberosTicket(GSSCredential gssCredentials, byte[] gssToken) throws GSSException {
        GSSContext context = gssManager.createContext(gssCredentials);
        // decrypt the kerberos ticket (GSS token)
        context.acceptSecContext(gssToken, 0, gssToken.length);

        // if we cannot decrypt the GSS Token we return the username as null
        if (!context.isEstablished()) {
            log.error("Unable to decrypt the kerberos ticket as context was not established.");
            return null;
        }

        String initiator = context.getSrcName().toString();
        String target = context.getTargName().toString();

        if (log.isDebugEnabled()) {
            String msg =
                    "Extracted details from GSS Token, Initiator : " + initiator + " , Intended target : " + target;
            log.debug(msg);
        }

        return initiator;
    }

    public boolean authorizeAccessDelegation(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        // if we need to just ignore the end user's extended verification

        return true;

        // if we need to verify with the end user's access delegation by calling callback chain.
        // However, you need to register a callback for this. Default call back just return true.

        //        OAuthCallback authzCallback = new OAuthCallback(
        //                tokReqMsgCtx.getAuthorizedUser(),
        //                tokReqMsgCtx.getOauth2AccessTokenReqDTO().getClientId(),
        //                OAuthCallback.OAuthCallbackType.ACCESS_DELEGATION_TOKEN);
        //        authzCallback.setRequestedScope(tokReqMsgCtx.getScope());
        //        authzCallback.setCarbonGrantType(org.wso2.carbon.identity.oauth.common.GrantType.valueOf(tokReqMsgCtx.
        //                                                            getOauth2AccessTokenReqDTO().getGrantType()));
        //        callbackManager.handleCallback(authzCallback);
        //        tokReqMsgCtx.setValidityPeriod(authzCallback.getValidityPeriod());
        //        return authzCallback.isAuthorized();

    }

    public boolean validateScope(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        // if we need to just ignore the scope verification

        return true;

        // if we need to verify with the scope n by calling callback chain.
        // However, you need to register a callback for this. Default call back just return true.
        // you can find more details on writing custom scope validator from here
        // http://xacmlinfo.org/2014/10/24/authorization-for-apis-with-xacml-and-oauth-2-0/

        //        OAuthCallback scopeValidationCallback = new OAuthCallback(
        //                tokReqMsgCtx.getAuthorizedUser().toString(),
        //                tokReqMsgCtx.getOauth2AccessTokenReqDTO().getClientId(),
        //                OAuthCallback.OAuthCallbackType.SCOPE_VALIDATION_TOKEN);
        //        scopeValidationCallback.setRequestedScope(tokReqMsgCtx.getScope());
        //        scopeValidationCallback.setCarbonGrantType(org.wso2.carbon.identity.oauth.common.GrantType.valueOf(tokReqMsgCtx.
        //                                                            getOauth2AccessTokenReqDTO().getGrantType()));
        //
        //        callbackManager.handleCallback(scopeValidationCallback);
        //        tokReqMsgCtx.setValidityPeriod(scopeValidationCallback.getValidityPeriod());
        //        tokReqMsgCtx.setScope(scopeValidationCallback.getApprovedScope());
        //        return scopeValidationCallback.isValidScope();
    }

    /**
     * Util method to get the Oid type for the received token
     * Eg:  SPENGO token will have Oid of "1.3.6.1.5.5.2"
     *      KER_5 tokens will have Oid of "1.2.840.113554.1.2.2"
     *
     * @param gssToken Received token converted to byte array
     * @return matching Oid
     * @throws IOException
     * @throws GSSException
     */
    private static Oid getOid(byte[] gssToken) throws IOException, GSSException {
        GSSHeader header = new GSSHeader(new ByteArrayInputStream(gssToken,0,gssToken.length));
        return GSSUtil.createOid(header.getOid().toString());
    }
}