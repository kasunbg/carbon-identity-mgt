/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.identity.mgt.store.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.mgt.bean.Attribute;
import org.wso2.carbon.identity.mgt.bean.Domain;
import org.wso2.carbon.identity.mgt.bean.Group;
import org.wso2.carbon.identity.mgt.bean.User;
import org.wso2.carbon.identity.mgt.callback.IdentityCallback;
import org.wso2.carbon.identity.mgt.claim.Claim;
import org.wso2.carbon.identity.mgt.claim.MetaClaim;
import org.wso2.carbon.identity.mgt.claim.MetaClaimMapping;
import org.wso2.carbon.identity.mgt.constant.UserCoreConstants;
import org.wso2.carbon.identity.mgt.context.AuthenticationContext;
import org.wso2.carbon.identity.mgt.exception.AuthenticationFailure;
import org.wso2.carbon.identity.mgt.exception.CredentialStoreException;
import org.wso2.carbon.identity.mgt.exception.DomainException;
import org.wso2.carbon.identity.mgt.exception.GroupNotFoundException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreClientException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreConnectorException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreServerException;
import org.wso2.carbon.identity.mgt.exception.UniqueIdResolverException;
import org.wso2.carbon.identity.mgt.exception.UserNotFoundException;
import org.wso2.carbon.identity.mgt.internal.IdentityMgtDataHolder;
import org.wso2.carbon.identity.mgt.model.GroupModel;
import org.wso2.carbon.identity.mgt.model.UserModel;
import org.wso2.carbon.identity.mgt.store.IdentityStore;
import org.wso2.carbon.identity.mgt.store.connector.CredentialStoreConnector;
import org.wso2.carbon.identity.mgt.user.ConnectedGroup;
import org.wso2.carbon.identity.mgt.user.UniqueGroup;
import org.wso2.carbon.identity.mgt.user.UniqueUser;
import org.wso2.carbon.identity.mgt.user.UserPartition;
import org.wso2.carbon.identity.mgt.util.IdentityUserMgtUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.security.auth.callback.Callback;

import static org.wso2.carbon.identity.mgt.util.IdentityMgtConstants.USERNAME_CLAIM;
import static org.wso2.carbon.kernel.utils.StringUtils.isNullOrEmpty;

/**
 * Represents a virtual identity store to abstract the underlying stores.
 *
 * @since 1.0.0
 */
public class IdentityStoreImpl implements IdentityStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityStoreImpl.class);

    private Map<String, Domain> domainNameToDomainMap = new HashMap<>();

    private SortedSet<Domain> sortedDomains = new TreeSet<>((d1, d2) -> {

        int d1Priority = d1.getDomainPriority();
        int d2Priority = d2.getDomainPriority();

        // Allow having multiple domains with the same priority
        if (d1Priority == d2Priority) {
            d2Priority++;
        }

        return Integer.compare(d1Priority, d2Priority);
    });

    @Override
    public void init(List<Domain> domains) throws IdentityStoreException {

        if (domains.isEmpty()) {
            throw new IdentityStoreException("No domains registered.");
        }

        sortedDomains.addAll(domains);
        domainNameToDomainMap = domains.stream().collect(Collectors.toMap(Domain::getDomainName, domain -> domain));

        if (log.isDebugEnabled()) {
            log.debug("Identity store successfully initialized.");
        }
    }

    /**
     * Identity User Management Read Operations.
     */

    @Override
    public User getUser(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetUser(uniqueUserId, domain);
    }

    @Override
    public User getUser(String uniqueUserId, String domainName) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getUser(uniqueUserId);
        }

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetUser(uniqueUserId, domain);
    }

    //TODO:ClaimWrapper handle uniqueness validation & claim dialect conversion
    @Override
    public User getUser(Claim claim) throws IdentityStoreException, UserNotFoundException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetUser(claim, domain);
    }

    //TODO:ClaimWrapper handle uniqueness validation & claim dialect conversion
    @Override
    public User getUser(Claim claim, String domainName) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getUser(claim);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetUser(claim, domain);
    }

    @Override
    public List<User> listUsers(int offset, int length) throws IdentityStoreException {

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListUsers(offset, length, domain);
    }

    @Override
    public List<User> listUsers(int offset, int length, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listUsers(offset, length);
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListUsers(offset, length, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion
    @Override
    public List<User> listUsers(Claim claim, int offset, int length) throws IdentityStoreException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListUsers(claim, offset, length, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion
    @Override
    public List<User> listUsers(Claim claim, int offset, int length, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listUsers(claim, offset, length);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListUsers(claim, offset, length, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion
    @Override
    public List<User> listUsers(MetaClaim metaClaim, String filterPattern, int offset, int length)
            throws IdentityStoreException {

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListUsers(metaClaim, filterPattern, offset, length, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion
    @Override
    public List<User> listUsers(MetaClaim metaClaim, String filterPattern, int offset, int length, String domainName)
            throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listUsers(metaClaim, filterPattern, offset, length);
        }

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListUsers(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public Group getGroup(String uniqueGroupId) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetGroup(uniqueGroupId, domain);
    }

    @Override
    public Group getGroup(String uniqueGroupId, String domainName) throws IdentityStoreException,
            GroupNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getGroup(uniqueGroupId);
        }

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetGroup(uniqueGroupId, domain);
    }

    //TODO:ClaimWrapper handle uniqueness validation & claim dialect conversion
    @Override
    public Group getGroup(Claim claim) throws IdentityStoreException, GroupNotFoundException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetGroup(claim, domain);
    }

    //TODO:ClaimWrapper handle uniqueness validation & claim dialect conversion
    @Override
    public Group getGroup(Claim claim, String domainName) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getGroup(claim);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetGroup(claim, domain);
    }

    @Override
    public List<Group> listGroups(int offset, int length) throws IdentityStoreException {

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListGroups(offset, length, domain);
    }

    @Override
    public List<Group> listGroups(int offset, int length, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listGroups(offset, length);
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListGroups(offset, length, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion
    @Override
    public List<Group> listGroups(Claim claim, int offset, int length) throws IdentityStoreException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListGroups(claim, offset, length, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion
    @Override
    public List<Group> listGroups(Claim claim, int offset, int length, String domainName) throws
            IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listGroups(claim, offset, length);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListGroups(claim, offset, length, domain);
    }

    @Override
    public List<Group> listGroups(MetaClaim metaClaim, String filterPattern, int offset, int length) throws
            IdentityStoreException {

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListGroups(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public List<Group> listGroups(MetaClaim metaClaim, String filterPattern, int offset, int length, String
            domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listGroups(metaClaim, filterPattern, offset, length);
        }

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListGroups(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public List<Group> getGroupsOfUser(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetGroupsOfUser(uniqueUserId, domain);
    }

    @Override
    public List<Group> getGroupsOfUser(String uniqueUserId, String domainName) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getGroupsOfUser(uniqueUserId);
        }

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetGroupsOfUser(uniqueUserId, domain);
    }

    @Override
    public List<User> getUsersOfGroup(String uniqueGroupId) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetUsersOfGroup(uniqueGroupId, domain);
    }

    @Override
    public List<User> getUsersOfGroup(String uniqueGroupId, String domainName) throws IdentityStoreException,
            GroupNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getUsersOfGroup(uniqueGroupId);
        }

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetUsersOfGroup(uniqueGroupId, domain);
    }

    @Override
    public boolean isUserInGroup(String uniqueUserId, String uniqueGroupId) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId) || isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid inputs.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doIsUserInGroup(uniqueUserId, uniqueGroupId, domain);
    }

    @Override
    public boolean isUserInGroup(String uniqueUserId, String uniqueGroupId, String domainName) throws
            IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return isUserInGroup(uniqueUserId, uniqueGroupId);
        }

        if (isNullOrEmpty(uniqueUserId) || isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid inputs.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doIsUserInGroup(uniqueUserId, uniqueGroupId, domain);
    }

    @Override
    public List<Claim> getClaims(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetClaims(uniqueUserId, domain);
    }

    @Override
    public List<Claim> getClaims(String uniqueUserId, String domainName) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getClaims(uniqueUserId);
        }

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetClaims(uniqueUserId, domain);
    }

    @Override
    public List<Claim> getClaims(String uniqueUserId, List<MetaClaim> metaClaims) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        if (metaClaims == null || metaClaims.isEmpty()) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetClaims(uniqueUserId, metaClaims, domain);
    }

    @Override
    public List<Claim> getClaims(String uniqueUserId, List<MetaClaim> metaClaims, String domainName) throws
            IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getClaims(uniqueUserId, metaClaims);
        }

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        if (metaClaims == null || metaClaims.isEmpty()) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetClaims(uniqueUserId, metaClaims, domain);
    }

    /**
     * Identity User Management Read Operations End.
     */

    /**
     * Identity User Management Write Operations
     */

    //TODO:ClaimWrapper handle claim dialect conversion, validate username claim existence
    @Override
    public User addUser(UserModel userModel) throws IdentityStoreException {

        if (userModel == null || (userModel.getClaims().isEmpty() && userModel.getCredentials().isEmpty())) {
            throw new IdentityStoreClientException("Invalid user.");
        }

        if (!userModel.getClaims().isEmpty() && !userModel.getClaims().stream().filter(claim -> USERNAME_CLAIM.equals
                (claim.getClaimUri()) && !isNullOrEmpty(claim.getValue())).findAny().isPresent()) {
            throw new IdentityStoreClientException("Valid username claim must be present.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doAddUser(userModel, domain);
    }

    //TODO:ClaimWrapper handle claim dialect conversion, validate username claim existence
    @Override
    public User addUser(UserModel userModel, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addUser(userModel);
        }

        if (userModel == null || (userModel.getClaims().isEmpty() && userModel.getCredentials().isEmpty())) {
            throw new IdentityStoreClientException("Invalid user.");
        }

        if (!userModel.getClaims().isEmpty() && !userModel.getClaims().stream().filter(claim -> USERNAME_CLAIM.equals
                (claim.getClaimUri()) && !isNullOrEmpty(claim.getValue())).findAny().isPresent()) {
            throw new IdentityStoreClientException("Valid username claim must be present.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doAddUser(userModel, domain);
    }

    @Override
    public List<User> addUsers(List<UserModel> userModels) throws IdentityStoreException {

        if (userModels == null || userModels.isEmpty()) {
            throw new IdentityStoreClientException("Invalid user list.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doAddUsers(userModels, domain);
    }

    @Override
    public List<User> addUsers(List<UserModel> userModels, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addUsers(userModels);
        }

        if (userModels == null || userModels.isEmpty()) {
            throw new IdentityStoreClientException("Invalid user list.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doAddUsers(userModels, domain);
    }

    @Override
    public void updateUserClaims(String uniqueUserId, List<Claim> claims, String domainName) throws
            IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        doUpdateUserClaims(uniqueUserId, claims, domain);
    }

    /**
     * Identity User Management Write Operations End.
     */

    /**
     * Identity User Management Authentication Related Operations.
     */

    @Override
    public AuthenticationContext authenticate(Claim claim, Callback credential, String domainName)
            throws AuthenticationFailure {

        if (claim == null || isNullOrEmpty(claim.getValue()) || credential == null) {
            throw new AuthenticationFailure("Invalid credentials.");
        }

        //TODO claim wrapper will handle the dialect conversion
//        try {
//            claim = claimManager.convertToDefaultClaimDialect(claim);
//        } catch (ClaimManagerException e) {
//            log.error("Failed to convert to the default claim dialect.", e);
//            throw new AuthenticationFailure("Provided claim is invalid.");
//        }

        if (!isNullOrEmpty(domainName)) {

            Domain domain;
            try {
                domain = getDomainFromDomainName(domainName);
            } catch (DomainException e) {
                log.error(String.format("Error while retrieving domain from the domain name - %s", domainName), e);
                throw new AuthenticationFailure("Domain name is invalid.");
            }
            return doAuthenticate(claim, credential, domain);
        }


        AuthenticationContext context = null;
        for (Domain domain : sortedDomains) {
            if (domain.isClaimSupported(claim.getClaimUri())) {
                try {
                    context = doAuthenticate(claim, credential, domain);
                    break;
                } catch (AuthenticationFailure e) {

                }
            }
        }

        if (context == null) {
            throw new AuthenticationFailure("Invalid credentials.");
        }
        return context;
    }

    /**
     * Identity User Management Authentication Related Operations End.
     */

    /**
     * Identity User Management private methods.
     */

    private User doGetUser(String uniqueUserId, Domain domain) throws IdentityStoreException, UserNotFoundException {

        boolean userExists;
        try {
            userExists = domain.getUniqueIdResolver().isUserExists(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (!userExists) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        return new User.UserBuilder()
                .setUserId(uniqueUserId)
                .setDomainName(domain.getDomainName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private User doGetUser(Claim claim, Domain domain) throws IdentityStoreException, UserNotFoundException {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(claim.getClaimUri());
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mapping for the claim URI.");
        }

        String connectorUserId = domain.getIdentityStoreConnectorFromId(metaClaimMapping.getIdentityStoreConnectorId())
                .getConnectorUserId(metaClaimMapping.getAttributeName(), claim.getValue());

        if (isNullOrEmpty(connectorUserId)) {
            throw new UserNotFoundException("Invalid claim value.");
        }

        UniqueUser uniqueUser;
        try {
            uniqueUser = domain.getUniqueIdResolver().getUniqueUserFromConnectorUserId(connectorUserId, metaClaimMapping
                    .getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException("Failed to retrieve the unique user id.", e);
        }

        if (uniqueUser == null || isNullOrEmpty(uniqueUser.getUniqueUserId())) {
            throw new IdentityStoreServerException("Failed to retrieve the unique user id.");
        }

        return new User.UserBuilder()
                .setUserId(uniqueUser.getUniqueUserId())
                .setDomainName(domain.getDomainName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private List<User> doListUsers(int offset, int length, Domain domain) throws IdentityStoreException {

        List<UniqueUser> uniqueUsers;
        try {
            uniqueUsers = domain.getUniqueIdResolver().listUsers(offset, length);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to list users in the domain - %s", domain), e);
        }

        if (uniqueUsers == null || uniqueUsers.isEmpty()) {
            return Collections.emptyList();
        }

        return uniqueUsers.stream()
                .map(uniqueUser -> new User.UserBuilder()
                        .setUserId(uniqueUser.getUniqueUserId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<User> doListUsers(Claim claim, int offset, int length, Domain domain) throws IdentityStoreException {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(claim.getClaimUri());
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mapping for the claim URI.");
        }

        List<String> connectorUserIds = domain.getIdentityStoreConnectorFromId(metaClaimMapping
                .getIdentityStoreConnectorId()).listConnectorUserIds(metaClaimMapping.getAttributeName(), claim
                .getValue(), offset, length);

        if (connectorUserIds == null || connectorUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<UniqueUser> uniqueUsers;
        try {
            uniqueUsers = domain.getUniqueIdResolver().getUniqueUsers(connectorUserIds, metaClaimMapping
                    .getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException("Failed to retrieve the unique user ids.", e);
        }

        if (uniqueUsers == null || uniqueUsers.isEmpty()) {
            throw new IdentityStoreServerException("Failed to retrieve the unique user ids.");
        }

        return uniqueUsers.stream()
                .map(uniqueUser -> new User.UserBuilder()
                        .setUserId(uniqueUser.getUniqueUserId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<User> doListUsers(MetaClaim metaClaim, String filterPattern, int offset, int length, Domain domain)
            throws IdentityStoreException {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(metaClaim.getClaimUri());
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mapping for the claim URI.");
        }

        List<String> connectorUserIds = domain.getIdentityStoreConnectorFromId(metaClaimMapping
                .getIdentityStoreConnectorId()).listConnectorUserIdsByPattern(metaClaimMapping.getAttributeName(),
                filterPattern, offset, length);

        if (connectorUserIds == null || connectorUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<UniqueUser> uniqueUsers;
        try {
            uniqueUsers = domain.getUniqueIdResolver().getUniqueUsers(connectorUserIds, metaClaimMapping
                    .getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException("Failed to retrieve the unique user ids.", e);
        }

        if (uniqueUsers == null || uniqueUsers.isEmpty()) {
            throw new IdentityStoreServerException("Failed to retrieve the unique user ids.");
        }

        return uniqueUsers.stream()
                .map(uniqueUser -> new User.UserBuilder()
                        .setUserId(uniqueUser.getUniqueUserId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private Group doGetGroup(String uniqueGroupId, Domain domain) throws IdentityStoreException,
            GroupNotFoundException {

        boolean groupExists;
        try {
            groupExists = domain.getUniqueIdResolver().isGroupExists(uniqueGroupId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique group - " +
                    "%s.", uniqueGroupId), e);
        }

        if (!groupExists) {
            throw new GroupNotFoundException("Invalid unique group id.");
        }

        return new Group.GroupBuilder()
                .setGroupId(uniqueGroupId)
                .setDomainName(domain.getDomainName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private Group doGetGroup(Claim claim, Domain domain) throws IdentityStoreException, GroupNotFoundException {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(claim.getClaimUri());
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mapping for the claim URI.");
        }

        String connectorGroupId = domain.getIdentityStoreConnectorFromId(metaClaimMapping.getIdentityStoreConnectorId())
                .getConnectorGroupId(metaClaimMapping.getAttributeName(), claim.getValue());

        if (isNullOrEmpty(connectorGroupId)) {
            throw new GroupNotFoundException("Invalid claim value.");
        }

        UniqueGroup uniqueGroup;
        try {
            uniqueGroup = domain.getUniqueIdResolver().getUniqueGroupFromConnectorGroupId(connectorGroupId,
                    metaClaimMapping.getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException("Failed retrieve the group unique id.", e);
        }

        if (uniqueGroup == null || isNullOrEmpty(uniqueGroup.getUniqueGroupId())) {
            throw new IdentityStoreServerException("Failed to retrieve the unique group id.");
        }

        return new Group.GroupBuilder()
                .setGroupId(uniqueGroup.getUniqueGroupId())
                .setDomainName(domain.getDomainName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private List<Group> doListGroups(int offset, int length, Domain domain) throws IdentityStoreException {

        List<UniqueGroup> uniqueGroups;
        try {
            uniqueGroups = domain.getUniqueIdResolver().listGroups(offset, length);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to list groups in the domain - %s", domain),
                    e);
        }

        if (uniqueGroups == null || uniqueGroups.isEmpty()) {
            return Collections.emptyList();
        }

        return uniqueGroups.stream()
                .map(uniqueGroup -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroup.getUniqueGroupId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<Group> doListGroups(Claim claim, int offset, int length, Domain domain) throws IdentityStoreException {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(claim.getClaimUri());
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mapping for the claim URI.");
        }

        List<String> connectorGroupIds = domain.getIdentityStoreConnectorFromId(metaClaimMapping
                .getIdentityStoreConnectorId()).listConnectorGroupIds(metaClaimMapping.getAttributeName(), claim
                .getValue(), offset, length);

        if (connectorGroupIds == null || connectorGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<UniqueGroup> uniqueGroups;
        try {
            uniqueGroups = domain.getUniqueIdResolver().getUniqueGroups(connectorGroupIds, metaClaimMapping
                    .getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException("Failed to retrieve the unique group ids.", e);
        }

        if (uniqueGroups == null || uniqueGroups.isEmpty()) {
            throw new IdentityStoreServerException("Failed to retrieve the unique group ids.");
        }

        return uniqueGroups.stream()
                .map(uniqueGroup -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroup.getUniqueGroupId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<Group> doListGroups(MetaClaim metaClaim, String filterPattern, int offset, int length, Domain domain)
            throws IdentityStoreException {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(metaClaim.getClaimUri());
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mapping for the claim URI.");
        }

        List<String> connectorGroupIds = domain.getIdentityStoreConnectorFromId(metaClaimMapping
                .getIdentityStoreConnectorId()).listConnectorGroupIdsByPattern(metaClaimMapping.getAttributeName(),
                filterPattern, offset, length);

        if (connectorGroupIds == null || connectorGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<UniqueGroup> uniqueGroups;
        try {
            uniqueGroups = domain.getUniqueIdResolver().getUniqueGroups(connectorGroupIds, metaClaimMapping
                    .getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException("Failed to retrieve the unique group ids.", e);
        }

        if (uniqueGroups == null || uniqueGroups.isEmpty()) {
            throw new IdentityStoreServerException("Failed to retrieve the unique group ids.");
        }

        return uniqueGroups.stream()
                .map(uniqueGroup -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroup.getUniqueGroupId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<Group> doGetGroupsOfUser(String uniqueUserId, Domain domain) throws IdentityStoreException,
            UserNotFoundException {

        boolean userExists;
        try {
            userExists = domain.getUniqueIdResolver().isUserExists(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (!userExists) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        List<UniqueGroup> uniqueGroups;
        try {
            uniqueGroups = domain.getUniqueIdResolver().getGroupsOfUser(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve the unique group ids for user id" +
                    " - %s.", uniqueUserId), e);
        }

        if (uniqueGroups == null || uniqueGroups.isEmpty()) {
            return Collections.emptyList();
        }

        return uniqueGroups.stream()
                .map(uniqueGroup -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroup.getUniqueGroupId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<User> doGetUsersOfGroup(String uniqueGroupId, Domain domain) throws IdentityStoreException,
            GroupNotFoundException {

        boolean groupExists;
        try {
            groupExists = domain.getUniqueIdResolver().isGroupExists(uniqueGroupId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique group - " +
                    "%s.", uniqueGroupId), e);
        }

        if (!groupExists) {
            throw new GroupNotFoundException("Invalid unique group id.");
        }

        List<UniqueUser> uniqueUsers;
        try {
            uniqueUsers = domain.getUniqueIdResolver().getUsersOfGroup(uniqueGroupId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve the unique user ids for group id" +
                    " - %s.", uniqueGroupId), e);
        }

        if (uniqueUsers == null || uniqueUsers.isEmpty()) {
            return Collections.emptyList();
        }

        return uniqueUsers.stream()
                .map(uniqueUser -> new User.UserBuilder()
                        .setUserId(uniqueUser.getUniqueUserId())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private boolean doIsUserInGroup(String uniqueUserId, String uniqueGroupId, Domain domain) throws
            IdentityStoreException, UserNotFoundException {

        boolean userExists;
        try {
            userExists = domain.getUniqueIdResolver().isUserExists(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (!userExists) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        try {
            return domain.getUniqueIdResolver().isUserInGroup(uniqueUserId, uniqueGroupId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to check unique user - %s belong to the " +
                    "group -  %s.", uniqueUserId, uniqueGroupId), e);
        }
    }

    private List<Claim> doGetClaims(String uniqueUserId, Domain domain) throws IdentityStoreException,
            UserNotFoundException {

        UniqueUser uniqueUser;
        try {
            uniqueUser = domain.getUniqueIdResolver().getUniqueUser(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (uniqueUser == null) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        if (uniqueUser.getUserPartitions() == null || uniqueUser.getUserPartitions().isEmpty()) {
            return Collections.emptyList();
        }

        List<UserPartition> userPartitions = uniqueUser.getUserPartitions().stream()
                .filter(UserPartition::isIdentityStore)
                .collect(Collectors.toList());

        if (userPartitions.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<Attribute>> connectorIdToAttributesMap = new HashMap<>();
        for (UserPartition userPartition : userPartitions) {
            connectorIdToAttributesMap.put(userPartition.getConnectorId(),
                    domain.getIdentityStoreConnectorFromId(userPartition.getConnectorId()).getUserAttributeValues
                            (userPartition.getConnectorUserId()));
        }

        List<MetaClaimMapping> metaClaimMappings;
        try {
            metaClaimMappings = domain.getMetaClaimMappings();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mappings.");
        }

        return buildClaims(metaClaimMappings, connectorIdToAttributesMap);
    }

    private List<Claim> doGetClaims(String uniqueUserId, List<MetaClaim> metaClaims, Domain domain) throws
            IdentityStoreException,
            UserNotFoundException {

        UniqueUser uniqueUser;
        try {
            uniqueUser = domain.getUniqueIdResolver().getUniqueUser(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (uniqueUser == null) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        if (uniqueUser.getUserPartitions() == null || uniqueUser.getUserPartitions().isEmpty()) {
            return Collections.emptyList();
        }

        List<UserPartition> userPartitions = uniqueUser.getUserPartitions().stream()
                .filter(UserPartition::isIdentityStore)
                .collect(Collectors.toList());

        if (userPartitions.isEmpty()) {
            return Collections.emptyList();
        }

        List<MetaClaimMapping> metaClaimMappings;
        try {
            metaClaimMappings = domain.getMetaClaimMappings();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve the meta claim mappings.");
        }

        Map<String, List<String>> connectorIdToAttributeNameMap = getConnectorIdToAttributeNameMap(metaClaimMappings,
                metaClaims);

        Map<String, List<Attribute>> connectorIdToAttributesMap = new HashMap<>();
        for (UserPartition userPartition : userPartitions) {
            List<String> attributeNames = connectorIdToAttributeNameMap.get(userPartition.getConnectorId());
            if (attributeNames != null) {
                connectorIdToAttributesMap.put(userPartition.getConnectorId(),
                        domain.getIdentityStoreConnectorFromId(userPartition.getConnectorId())
                                .getUserAttributeValues(userPartition.getConnectorUserId(), attributeNames));
            }
        }

        return buildClaims(metaClaimMappings, connectorIdToAttributesMap);
    }

    private User doAddUser(UserModel userModel, Domain domain) throws IdentityStoreException {

        List<UserPartition> userPartitions = new ArrayList<>();

        if (!userModel.getClaims().isEmpty()) {
            List<MetaClaimMapping> metaClaimMappings;
            try {
                metaClaimMappings = domain.getMetaClaimMappings();
            } catch (DomainException e) {
                throw new IdentityStoreServerException("Failed to retrieve meta claim mappings.");
            }

            Map<String, List<Attribute>> connectorIdToAttributesMap = getConnectorIdToAttributesMap(userModel
                    .getClaims(), metaClaimMappings);


            for (Map.Entry<String, List<Attribute>> entry : connectorIdToAttributesMap.entrySet()) {
                String connectorUserId;
                try {
                    connectorUserId = domain.getIdentityStoreConnectorFromId(entry.getKey()).addUser(entry.getValue());
                } catch (IdentityStoreConnectorException e) {
                    // Recover from the inconsistent state in the connectors
                    if (userPartitions.size() > 0) {
                        removeAddedUsersInAFailure(domain, userPartitions);
                    }
                    throw new IdentityStoreServerException("Identity store connector failed to add user attributes.",
                            e);
                }

                userPartitions.add(new UserPartition(entry.getKey(), connectorUserId, true));
            }
        }

        if (!userModel.getCredentials().isEmpty()) {
            Map<String, List<Callback>> connectorIdToCredentialsMap = getConnectorIdToCredentialsMap(userModel
                    .getCredentials(), domain.getCredentialStoreConnectors());

            for (Map.Entry<String, List<Callback>> entry : connectorIdToCredentialsMap.entrySet()) {

                String connectorUserId;
                try {
                    connectorUserId = domain.getCredentialStoreConnectorFromId(entry.getKey()).addCredential(
                            entry.getValue().toArray(new Callback[entry.getValue().size()]));
                } catch (CredentialStoreException e) {
                    // Recover from the inconsistent state in the connectors
                    if (userPartitions.size() > 0) {
                        removeAddedUsersInAFailure(domain, userPartitions);
                    }
                    throw new IdentityStoreServerException("Credential store connector failed to add user attributes.",
                            e);
                }

                userPartitions.add(new UserPartition(entry.getKey(), connectorUserId, false));
            }
        }

        String userUniqueId = IdentityUserMgtUtil.generateUUID();
        try {
            domain.getUniqueIdResolver().addUser(new UniqueUser(userUniqueId, userPartitions), domain.getDomainName());
        } catch (UniqueIdResolverException e) {
            // Recover from the inconsistent state in the connectors
            removeAddedUsersInAFailure(domain, userPartitions);

            throw new IdentityStoreServerException("Error occurred while persisting user unique id.", e);
        }

        return new User.UserBuilder()
                .setUserId(userUniqueId)
                .setDomainName(domain.getDomainName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private void doUpdateUserClaims(String uniqueUserId, List<Claim> claims, Domain domain) throws
            IdentityStoreException, UserNotFoundException {

        UniqueUser uniqueUser;
        try {
            uniqueUser = domain.getUniqueIdResolver().getUniqueUser(uniqueUserId);
        } catch (UniqueIdResolverException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve unique user - %s.",
                    uniqueUserId), e);
        }

        if (uniqueUser == null) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        Map<String, String> existingConnectorIdToConnectorUserIdMap = new HashMap<>();

        if (!uniqueUser.getUserPartitions().isEmpty()) {
            existingConnectorIdToConnectorUserIdMap.putAll(uniqueUser.getUserPartitions().stream()
                    .filter(UserPartition::isIdentityStore)
                    .collect(Collectors.toMap(UserPartition::getConnectorId, UserPartition::getConnectorUserId)));
        }

        Map<String, String> updatedUniqueIds = new HashMap<>();

        if ((claims == null || claims.isEmpty()) && !existingConnectorIdToConnectorUserIdMap.isEmpty()) {
            for (Map.Entry<String, String> entry : existingConnectorIdToConnectorUserIdMap.entrySet()) {
                String updatedConnectorUserId = domain.getIdentityStoreConnectorFromId(entry.getKey())
                        .updateUserAttributes(entry.getValue(), new ArrayList<>());
                updatedUniqueIds.put(entry.getKey(), updatedConnectorUserId);
            }
        } else {
            List<MetaClaimMapping> metaClaimMappings;
            try {
                metaClaimMappings = domain.getMetaClaimMappings();
            } catch (DomainException e) {
                throw new IdentityStoreServerException("Failed to retrieve meta claim mappings.");
            }

            Map<String, List<Attribute>> connectorIdToAttributesMap = getConnectorIdToAttributesMap(claims,
                    metaClaimMappings);

            Map<String, String> connectorIdToConnectorUserIdMap = connectorIdToAttributesMap.keySet().stream()
                    .collect(Collectors.toMap(connectorId -> connectorId, connectorId -> null));

            connectorIdToConnectorUserIdMap.putAll(existingConnectorIdToConnectorUserIdMap);

            for (Map.Entry<String, String> entry : connectorIdToConnectorUserIdMap.entrySet()) {

                String updatedConnectorUserId;
                if (isNullOrEmpty(entry.getValue())) {
                    try {
                        updatedConnectorUserId = domain.getIdentityStoreConnectorFromId(entry.getKey())
                                .addUser(connectorIdToAttributesMap.get(entry.getKey()));
                    } catch (IdentityStoreConnectorException e) {
                        throw new IdentityStoreServerException("Identity store connector failed to add user " +
                                "attributes.", e);
                    }
                } else {
                    updatedConnectorUserId = domain.getIdentityStoreConnectorFromId(entry.getKey())
                            .updateUserAttributes(entry.getValue(), connectorIdToAttributesMap.get(entry.getKey()));
                }
                updatedUniqueIds.put(entry.getKey(), updatedConnectorUserId);
            }
        }

        if (!existingConnectorIdToConnectorUserIdMap.equals(updatedUniqueIds)) {
            try {
                domain.getUniqueIdResolver().updateUser(uniqueUserId, updatedUniqueIds);
            } catch (UniqueIdResolverException e) {
                throw new IdentityStoreServerException("Failed to update user connector ids.", e);
            }
        }
    }

    private List<User> doAddUsers(List<UserModel> userModels, Domain domain) throws IdentityStoreException {

//        for (UserModel userModel : userModels) {
//            try {
//                userModel.setClaims(claimManager.convertToDefaultClaimDialect(userModel.getClaims()));
//            } catch (ClaimManagerException e) {
//                throw new IdentityStoreServerException("Failed to convert to the default claim dialect.", e);
//            }
//        }

//        Map<String, UserModel> userUniqueIdToUserModelMap = userModels.stream().collect(Collectors.toMap
//                (userModel -> IdentityUserMgtUtil.generateUUID(), userModel -> userModel));

        List<MetaClaimMapping> metaClaimMappings;
        try {
            metaClaimMappings = domain.getMetaClaimMappings();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to retrieve meta claim mappings.");
        }

        List<Map<String, List<Attribute>>> connectorIdToAttributesMaps = userModels.stream()
                .filter(Objects::nonNull)
                .map(userModel -> getConnectorIdToAttributesMap(userModel.getClaims(), metaClaimMappings))
                .collect(Collectors.toList());

        Map<String, Map<String, List<Attribute>>> connectorViseUserMap = getConnectorViseAttributesMap
                (connectorIdToAttributesMaps);

        Map<String, List<UserPartition>> connectedUsersList = new HashMap<>();

        for (Map.Entry<String, Map<String, List<Attribute>>> entry : connectorViseUserMap.entrySet()) {

            Map<String, String> uniqueIds = domain.getIdentityStoreConnectorFromId(entry.getKey())
                    .addUsers(entry.getValue());

            if (uniqueIds != null) {
                uniqueIds.entrySet().stream()
                        .forEach(t -> {
                            List<UserPartition> userPartitions = connectedUsersList.get(t.getKey());
                            if (userPartitions == null) {
                                userPartitions = new ArrayList<>();
                                connectedUsersList.put(t.getKey(), userPartitions);
                            }
                            userPartitions.add(new UserPartition(entry.getKey(), t.getValue(), true));
                        });
            }
            // TODO handle any failure
        }

        try {
            domain.getUniqueIdResolver().addUsers(connectedUsersList);
        } catch (UniqueIdResolverException e) {
            // TODO handle any failure
            throw new IdentityStoreServerException("Error occurred while persisting user unique ids.", e);
        }

        return connectedUsersList.entrySet().stream()
                .map(entry -> new User.UserBuilder()
                        .setUserId(entry.getKey())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getRealmService()
                                .getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private AuthenticationContext doAuthenticate(Claim claim, Callback credential, Domain domain)
            throws AuthenticationFailure {

        MetaClaimMapping metaClaimMapping;
        try {
            metaClaimMapping = domain.getMetaClaimMapping(claim.getClaimUri());
        } catch (DomainException e) {
            throw new AuthenticationFailure("Failed to retrieve the meta claim mapping for the claim URI.", e);
        }

        if (!metaClaimMapping.isUnique()) {
            throw new AuthenticationFailure("Provided claim is not unique.");
        }

        String connectorUserId;
        try {
            connectorUserId = domain.getIdentityStoreConnectorFromId(metaClaimMapping.getIdentityStoreConnectorId())
                    .getConnectorUserId(metaClaimMapping.getAttributeName(), claim.getValue());
        } catch (UserNotFoundException | IdentityStoreException e) {
            throw new AuthenticationFailure("Invalid claim value. No user mapped to the provided claim.", e);
        }

        UniqueUser uniqueUser;
        try {
            uniqueUser = domain.getUniqueIdResolver().getUniqueUserFromConnectorUserId(connectorUserId, metaClaimMapping
                    .getIdentityStoreConnectorId());
        } catch (UniqueIdResolverException e) {
            throw new AuthenticationFailure("Failed retrieve unique user info.", e);
        }

        for (UserPartition userPartition : uniqueUser.getUserPartitions()) {
            if (!userPartition.isIdentityStore()) {
                CredentialStoreConnector connector = domain.getCredentialStoreConnectorFromId(userPartition
                        .getConnectorId());
                IdentityCallback<Map> carbonCallback = new IdentityCallback<>(null);

                Callback[] callbacks = new Callback[2];
                Map<String, String> userData = new HashMap<>();
                userData.put(UserCoreConstants.USER_ID, userPartition.getConnectorUserId());
                carbonCallback.setContent(userData);

                callbacks[0] = credential;
                callbacks[1] = carbonCallback;
                if (connector.canHandle(callbacks)) {
                    try {
                        connector.authenticate(callbacks);

                        return new AuthenticationContext(new User.UserBuilder()
                                .setUserId(uniqueUser.getUniqueUserId())
                                .setIdentityStore(this)
                                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getRealmService()
                                        .getAuthorizationStore())
                                .setDomainName(domain.getDomainName())
                                .build());
                    } catch (CredentialStoreException e) {
                        throw new AuthenticationFailure("Failed to authenticate from the provided credential.", e);
                    }
                }
            }
        }

        throw new AuthenticationFailure("Failed to authenticate user.");
    }

    private Domain getPrimaryDomain() throws DomainException {

        Domain domain = sortedDomains.first();

        if (domain == null) {
            throw new DomainException("No domains registered.");
        }

        return domain;
    }

    public Domain getDomainFromDomainName(String domainName) throws DomainException {

        Domain domain = domainNameToDomainMap.get(domainName);

        if (domain == null) {
            throw new DomainException(String.format("Domain %s was not found", domainName));
        }

        return domain;
    }


    @Override
    public void updateUserClaims(String uniqueUserId, List<Claim> userClaimsToUpdate, List<Claim> userClaimsToRemove)
            throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueUserId)) {
//            throw new IdentityStoreClientException("Invalid user unique id.");
//        }
//
//        String domainName;
//        try {
//            domainName = uniqueIdResolver.getDomainNameFromUserUniqueId(uniqueUserId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreClientException("Invalid user unique id. Failed to retrieve domain name.");
//        }
//
//        if ((userClaimsToUpdate == null || userClaimsToUpdate.isEmpty()) && (userClaimsToRemove == null ||
//                userClaimsToRemove.isEmpty())) {
//            return;
//        }
//
//        Domain domain;
//        try {
//            domain = getDomainFromDomainName(domainName);
//        } catch (DomainException e) {
//            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain
// name " +
//                    "- %s", domainName), e);
//        }
//
//        doUpdateUserClaims(uniqueUserId, userClaimsToUpdate, userClaimsToRemove, domain);
    }

    @Override
    public void deleteUser(String uniqueUserId) throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueUserId)) {
//            throw new IdentityStoreClientException("Invalid user unique id.");
//        }
//
//        String domainName;
//        try {
//            domainName = uniqueIdResolver.getDomainNameFromUserUniqueId(uniqueUserId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreClientException("Invalid user unique id. Failed to retrieve domain name.");
//        }
//
//        Domain domain;
//        try {
//            domain = getDomainFromDomainName(domainName);
//        } catch (DomainException e) {
//            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain
// name " +
//                    "- %s", domainName), e);
//        }
//
//        Map<String, String> connectorUserIds;
//        try {
//            connectorUserIds = uniqueIdResolver.getConnectorUserIds(uniqueUserId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException(String.format("Failed to retrieve connector id to user connector
// " +
//                    "id map for unique user id - %s", uniqueUserId), e);
//        }
//
//        for (Map.Entry<String, String> entry : connectorUserIds.entrySet()) {
//            domain.getIdentityStoreConnectorFromId(entry.getKey()).deleteUser(entry.getValue());
//        }
//
//        try {
//            uniqueIdResolver.deleteUser(uniqueUserId);
//            //TODO audit log
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException("Failed to delete user - " + uniqueUserId, e);
//        }
    }

    @Override
    public void updateGroupsOfUser(String uniqueUserId, List<String> uniqueGroupIds) throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueUserId)) {
//            throw new IdentityStoreClientException("Invalid user unique id.");
//        }
//
//        try {
//            uniqueIdResolver.updateGroupsOfUser(uniqueUserId, uniqueGroupIds);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException("Failed to update groups of user - " + uniqueUserId, e);
//        }
    }

    @Override
    public void updateGroupsOfUser(String uniqueUserId, List<String> uniqueGroupIdsToAdd, List<String>
            uniqueGroupIdsToRemove) throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueUserId)) {
//            throw new IdentityStoreClientException("Invalid user unique id.");
//        }
//
//        try {
//            uniqueIdResolver.updateGroupsOfUser(uniqueUserId, uniqueGroupIdsToAdd, uniqueGroupIdsToRemove);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException("Failed to update groups of user - " + uniqueUserId, e);
//        }
    }

    @Override
    public Group addGroup(GroupModel groupModel) throws IdentityStoreException {

        if (groupModel == null || groupModel.getGroupClaims() == null || groupModel.getGroupClaims().isEmpty()) {
            throw new IdentityStoreClientException("Invalid group or claim list is empty.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving primary domain.", e);
        }

        return doAddGroup(groupModel, domain);
    }

    @Override
    public Group addGroup(GroupModel groupModel, String domainName) throws IdentityStoreException {

        if (groupModel == null || groupModel.getGroupClaims() == null || groupModel.getGroupClaims().isEmpty()) {
            throw new IdentityStoreClientException("Invalid group or claim list is empty.");
        }

        if (isNullOrEmpty(domainName)) {
            return addGroup(groupModel);
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doAddGroup(groupModel, domain);
    }

    @Override
    public List<Group> addGroups(List<GroupModel> groupModels) throws IdentityStoreException {

        if (groupModels == null || groupModels.isEmpty()) {
            throw new IdentityStoreClientException("Invalid group list. Group list is null or empty.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving primary domain.", e);
        }

        return doAddGroups(groupModels, domain);
    }

    @Override
    public List<Group> addGroups(List<GroupModel> groupModels, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addGroups(groupModels);
        }

        if (groupModels == null || groupModels.isEmpty()) {
            throw new IdentityStoreClientException("Invalid group list. Group list is null or empty.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doAddGroups(groupModels, domain);
    }

    @Override
    public void updateGroupClaims(String uniqueGroupId, List<Claim> groupClaims) throws IdentityStoreException {
//
//        if (StringUtils.isNullOrEmpty(uniqueGroupId)) {
//            throw new IdentityStoreClientException("Invalid group unique id.");
//        }
//
//        String domainName;
//        try {
//            domainName = uniqueIdResolver.getDomainNameFromGroupUniqueId(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreClientException("Invalid group unique id. Failed to retrieve domain name.");
//        }
//
//        Domain domain;
//        try {
//            domain = getDomainFromDomainName(domainName);
//        } catch (DomainException e) {
//            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain
// name " +
//                    "- %s", domainName), e);
//        }
//
//        doUpdateGroupClaims(uniqueGroupId, groupClaims, domain);
    }

    @Override
    public void updateGroupClaims(String uniqueGroupId, List<Claim> groupClaimsToUpdate,
                                  List<Claim> groupClaimsToRemove) throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueGroupId)) {
//            throw new IdentityStoreClientException("Invalid group unique id.");
//        }
//
//        String domainName;
//        try {
//            domainName = uniqueIdResolver.getDomainNameFromGroupUniqueId(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreClientException("Invalid group unique id. Failed to retrieve domain name.");
//        }
//
//        if ((groupClaimsToUpdate == null || groupClaimsToUpdate.isEmpty()) && (groupClaimsToRemove == null ||
//                groupClaimsToRemove.isEmpty())) {
//            return;
//        }
//
//        Domain domain;
//        try {
//            domain = getDomainFromDomainName(domainName);
//        } catch (DomainException e) {
//            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain
// name " +
//                    "- %s", domainName), e);
//        }
//
//        doUpdateGroupClaims(uniqueGroupId, groupClaimsToUpdate, groupClaimsToRemove, domain);
    }

    @Override
    public void deleteGroup(String uniqueGroupId) throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueGroupId)) {
//            throw new IdentityStoreClientException("Invalid group unique id.");
//        }
//
//        String domainName;
//        try {
//            domainName = uniqueIdResolver.getDomainNameFromUserUniqueId(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreClientException("Invalid group unique id. Failed to retrieve domain name.");
//        }
//
//        Domain domain;
//        try {
//            domain = getDomainFromDomainName(domainName);
//        } catch (DomainException e) {
//            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain
// name " +
//                    "- %s", domainName), e);
//        }
//
//        Map<String, String> connectorGroupIds;
//        try {
//            connectorGroupIds = uniqueIdResolver.getConnectorGroupIds(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException(String.format("Failed to retrieve connector id to group
// connector " +
//                    "id map for unique group id - %s", uniqueGroupId), e);
//        }
//
//        for (Map.Entry<String, String> entry : connectorGroupIds.entrySet()) {
//            domain.getIdentityStoreConnectorFromId(entry.getKey()).deleteGroup(entry.getValue());
//        }
//
//        try {
//            uniqueIdResolver.deleteGroup(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException(String.format("Failed to delete group - %s", uniqueGroupId), e);
//        }
    }

    @Override
    public void updateUsersOfGroup(String uniqueGroupId, List<String> uniqueUserIds) throws IdentityStoreException {


//        if (StringUtils.isNullOrEmpty(uniqueGroupId)) {
//            throw new IdentityStoreClientException("Invalid group unique id.");
//        }
//
//        try {
//            uniqueIdResolver.updateGroupsOfUser(uniqueGroupId, uniqueUserIds);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException(String.format("Failed to update users of group - %s",
//                    uniqueGroupId), e);
//        }
    }

    @Override
    public void updateUsersOfGroup(String uniqueGroupId, List<String> uniqueUserIdsToAdd,
                                   List<String> uniqueUserIdsToRemove) throws IdentityStoreException {

//        if (StringUtils.isNullOrEmpty(uniqueGroupId)) {
//            throw new IdentityStoreClientException("Invalid group unique id.");
//        }
//
//        try {
//            uniqueIdResolver.updateUsersOfGroup(uniqueGroupId, uniqueUserIdsToAdd, uniqueUserIdsToRemove);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException("Failed to update groups of user - " + uniqueGroupId, e);
//        }
    }


//    private void doUpdateUserClaims(String userUniqueId, List<Claim> userClaimsToUpdate, List<Claim>
// userClaimsToRemove,
//                                    Domain domain) throws IdentityStoreException {
//
//        Map<String, String> connectorUserIds;
//        try {
//            connectorUserIds = domain.getUniqueIdResolver().getConnectorUserIds(userUniqueId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException(String.format("Failed to retrieve connector id to user connector
// " +
//                    "id map for the user unique id - %s ", userUniqueId), e);
//        }
//
//        Map<String, List<MetaClaimMapping>> metaClaimMappings = domain.getConnectorIdToMetaClaimMappings();
//        if (metaClaimMappings.isEmpty()) {
//            throw new IdentityStoreServerException("Invalid identity store configuration found.");
//        }
//
//        Map<String, List<Attribute>> connectorAttributeMapToUpdate = getConnectorAttributesMap(userClaimsToUpdate,
//                metaClaimMappings);
//
//        Map<String, List<Attribute>> connectorAttributeMapToRemove = getConnectorAttributesMap(userClaimsToRemove,
//                metaClaimMappings);
//
//        Map<String, String> updatedConnectorUserIds = new HashMap<>();
//
//        for (Map.Entry<String, String> entry : connectorUserIds.entrySet()) {
//            String uniqueId = domain.getIdentityStoreConnectorFromId(entry.getKey())
//                    .updateUserAttributes(connectorUserIds.get(entry.getKey()), connectorAttributeMapToUpdate
//                            .get(entry.getKey()), connectorAttributeMapToRemove.get(entry.getKey()));
//            updatedConnectorUserIds.put(entry.getKey(), uniqueId);
//        }
//
//
//        if (!connectorUserIds.equals(updatedConnectorUserIds)) {
//            try {
//                domain.getUniqueIdResolver().updateUser(userUniqueId, updatedConnectorUserIds);
//            } catch (UniqueIdResolverException e) {
//                throw new IdentityStoreServerException("Failed to update user connected ids.", e);
//            }
//        }
//    }

    private Group doAddGroup(GroupModel groupModel, Domain domain) throws IdentityStoreException {

        Map<String, List<MetaClaimMapping>> metaClaimMappings = domain.getConnectorIdToMetaClaimMappings();
        if (metaClaimMappings.isEmpty()) {
            throw new IdentityStoreServerException("Invalid domain configuration found. No meta claim mappings.");
        }

        Map<String, List<Attribute>> connectorAttributeMap = getConnectorAttributesMap(groupModel.getGroupClaims(),
                metaClaimMappings);

        //TODO check group is present

        List<ConnectedGroup> connectedGroups = new ArrayList<>();
        for (Map.Entry<String, List<Attribute>> entry : connectorAttributeMap.entrySet()) {
            String uniqueId = domain.getIdentityStoreConnectorFromId(entry.getKey()).addGroup(entry.getValue());
            connectedGroups.add(new ConnectedGroup(entry.getKey(), uniqueId));
            // TODO handle any failure
        }

        String groupUniqueId = IdentityUserMgtUtil.generateUUID();
        try {
            domain.getUniqueIdResolver().addGroup(groupUniqueId, connectedGroups);
        } catch (UniqueIdResolverException e) {
            // TODO handle any failure
            throw new IdentityStoreServerException("Error occurred while persisting group unique group id.", e);
        }

        return new Group.GroupBuilder()
                .setGroupId(groupUniqueId)
                .setDomainName(domain.getDomainName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getRealmService()
                        .getAuthorizationStore())
                .build();
    }

    private List<Group> doAddGroups(List<GroupModel> groupModels, Domain domain) throws IdentityStoreException {

        Map<String, List<MetaClaimMapping>> metaClaimMappings = domain.getConnectorIdToMetaClaimMappings();
        if (metaClaimMappings.isEmpty()) {
            throw new IdentityStoreServerException("Invalid domain configuration found. No meta claim mappings.");
        }

        List<Map<String, List<Attribute>>> connectorAttributesMaps = groupModels.stream()
                .filter(Objects::nonNull)
                .filter(userModel -> userModel.getGroupClaims() != null)
                .map(groupModel -> getConnectorAttributesMap(groupModel.getGroupClaims(), metaClaimMappings))
                .collect(Collectors.toList());

        Map<String, Map<String, List<Attribute>>> connectorViseGroupMap = getConnectorViseAttributesMap
                (connectorAttributesMaps);

        Map<String, List<ConnectedGroup>> connectedGroupsMaps = new HashMap<>();

        for (Map.Entry<String, Map<String, List<Attribute>>> entry : connectorViseGroupMap.entrySet()) {

            Map<String, String> uniqueIds = domain.getIdentityStoreConnectorFromId(entry.getKey()).
                    addGroups(entry.getValue());

            if (uniqueIds != null) {
                uniqueIds.entrySet().stream()
                        .forEach(t -> {
                            List<ConnectedGroup> connectedGroups = connectedGroupsMaps.get(t.getKey());
                            if (connectedGroups == null) {
                                connectedGroups = new ArrayList<>();
                                connectedGroupsMaps.put(t.getKey(), connectedGroups);
                            }
                            connectedGroups.add(new ConnectedGroup(entry.getKey(), t.getValue()));
                        });
            }
            // TODO handle any failure
        }

        try {
            domain.getUniqueIdResolver().addGroups(connectedGroupsMaps);
        } catch (UniqueIdResolverException e) {
            // TODO handle any failure
            throw new IdentityStoreServerException("Error occurred while persisting group unique ids.", e);
        }

        return connectedGroupsMaps.entrySet().stream()
                .map(entry -> new Group.GroupBuilder()
                        .setGroupId(entry.getKey())
                        .setDomainName(domain.getDomainName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getRealmService()
                                .getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

//    private void doUpdateGroupClaims(String uniqueGroupId, List<Claim> groupClaims, Domain domain)
//            throws IdentityStoreException {
//
//        Map<String, String> connectorGroupIds;
//        try {
//            connectorGroupIds = domain.getUniqueIdResolver().getConnectorGroupIds(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException(String.format("Failed to retrieve connector id to group
// connector " +
//                    "id map for unique group id - %s", uniqueGroupId), e);
//        }
//
//        Map<String, String> updatedUniqueIds = new HashMap<>();
//
//        if (groupClaims == null || groupClaims.isEmpty()) {
//            connectorGroupIds.entrySet().stream()
//                    .forEach(rethrowConsumer(entry -> {
//                        domain.getIdentityStoreConnectorFromId(entry.getKey()).updateGroupAttributes(entry.getValue(),
//                                new ArrayList<>());
//                    }));
//            //TODO: do we need to delete group unique id? credential store still may have references
//        } else {
//
//            Map<String, List<MetaClaimMapping>> metaClaimMappings = domain.getConnectorIdToMetaClaimMappings();
//            if (metaClaimMappings.isEmpty()) {
//                throw new IdentityStoreServerException("Invalid domain configuration found. No meta claim mappings.");
//            }
//
//            Map<String, List<Attribute>> connectorAttributeMap = getConnectorAttributesMap(groupClaims,
//                    metaClaimMappings);
//
//            for (Map.Entry<String, String> entry : connectorGroupIds.entrySet()) {
//                String uniqueConnectorId = domain.getIdentityStoreConnectorFromId(entry.getKey())
// .updateGroupAttributes
//                        (connectorGroupIds.get(entry.getKey()), connectorAttributeMap.get(entry.getKey()));
//                updatedUniqueIds.put(entry.getKey(), uniqueConnectorId);
//            }
//        }
//
//        if (!connectorGroupIds.equals(updatedUniqueIds)) {
//            try {
//                domain.getUniqueIdResolver().updateGroup(uniqueGroupId, updatedUniqueIds);
//            } catch (UniqueIdResolverException e) {
//                throw new IdentityStoreServerException("Failed to update group connected ids.", e);
//            }
//        }
//    }

//    private void doUpdateGroupClaims(String uniqueGroupId, List<Claim> groupClaimsToUpdate,
//                                     List<Claim> groupClaimsToRemove, Domain domain) throws IdentityStoreException {
//
//        Map<String, String> connectorGroupIds;
//        try {
//            connectorGroupIds = domain.getUniqueIdResolver().getConnectorGroupIds(uniqueGroupId);
//        } catch (UniqueIdResolverException e) {
//            throw new IdentityStoreServerException("Failed to retrieve connector id to group connector id map for " +
//                    "for unique group id - %s" + uniqueGroupId, e);
//        }
//
//        Map<String, List<MetaClaimMapping>> metaClaimMappings = domain.getConnectorIdToMetaClaimMappings();
//        if (metaClaimMappings.isEmpty()) {
//            throw new IdentityStoreServerException("Invalid domain configuration found. No meta claim mappings.");
//        }
//
//        Map<String, List<Attribute>> connectorAttributeMapToUpdate = getConnectorAttributesMap(groupClaimsToUpdate,
//                metaClaimMappings);
//
//        Map<String, List<Attribute>> connectorAttributeMapToRemove = getConnectorAttributesMap(groupClaimsToRemove,
//                metaClaimMappings);
//
//        Map<String, String> updatedUniqueIds = new HashMap<>();
//
//        for (Map.Entry<String, String> entry : connectorGroupIds.entrySet()) {
//            String uniqueId = domain.getIdentityStoreConnectorFromId(entry.getKey()).updateGroupAttributes
//                    (connectorGroupIds.get(entry.getKey()), connectorAttributeMapToUpdate.get(entry.getKey()),
//                            connectorAttributeMapToRemove.get(entry.getKey()));
//            updatedUniqueIds.put(entry.getKey(), uniqueId);
//        }
//
//
//        if (!connectorGroupIds.equals(updatedUniqueIds)) {
//            try {
//                domain.getUniqueIdResolver().updateGroup(uniqueGroupId, updatedUniqueIds);
//            } catch (UniqueIdResolverException e) {
//                throw new IdentityStoreServerException("Failed to update group connected ids.", e);
//            }
//        }
//    }


    private Map<String, List<Attribute>> getConnectorIdToAttributesMap(List<Claim> claims,
                                                                       List<MetaClaimMapping> metaClaimMappings) {

        Map<String, List<Attribute>> connectorIdToAttributesMap = new HashMap<>();

        claims.stream()
                .forEach(claim -> {
                            Optional<MetaClaimMapping> optional = metaClaimMappings.stream()
                                    .filter(metaClaimMapping -> metaClaimMapping.getMetaClaim().getClaimUri()
                                            .equals(claim.getClaimUri()))
                                    .findFirst();

                            if (optional.isPresent()) {
                                MetaClaimMapping metaClaimMapping = optional.get();
                                List<Attribute> attributes = connectorIdToAttributesMap.get(metaClaimMapping
                                        .getIdentityStoreConnectorId());
                                if (attributes == null) {
                                    attributes = new ArrayList<>();
                                    connectorIdToAttributesMap.put(metaClaimMapping.getIdentityStoreConnectorId(),
                                            attributes);
                                }
                                attributes.add(new Attribute(metaClaimMapping.getAttributeName(), claim.getValue()));
                            }
                        }
                );

        return connectorIdToAttributesMap;
    }

    private List<Claim> buildClaims(List<MetaClaimMapping> metaClaimMappings, Map<String, List<Attribute>>
            connectorIdToAttributesMap) {

        List<Claim> claims = new ArrayList<>();

        connectorIdToAttributesMap.entrySet().stream()
                .filter(entry -> entry.getValue() == null || entry.getValue().isEmpty())
                .forEach(entry -> {
                            entry.getValue().stream()
                                    .forEach(attribute -> {
                                                Optional<MetaClaim> optional = metaClaimMappings.stream()
                                                        .filter(metaClaimMapping -> metaClaimMapping.getAttributeName()
                                                                .equals(attribute.getAttributeName()))
                                                        .map(MetaClaimMapping::getMetaClaim)
                                                        .findAny();

                                                if (optional.isPresent()) {
                                                    MetaClaim metaClaim = optional.get();
                                                    claims.add(new Claim(metaClaim.getDialectUri(), metaClaim
                                                            .getClaimUri(), attribute.getAttributeValue()));
                                                }
                                            }
                                    );
                        }
                );

        return claims;
    }

    private Map<String, List<Callback>> getConnectorIdToCredentialsMap(
            List<Callback> credentials, List<CredentialStoreConnector> credentialStoreConnectors) {

        Map<String, List<Callback>> connectorIdToCredentialsMap = new HashMap<>();

        credentials.stream()
                .filter(Objects::nonNull)
                .forEach(callback -> {
                    Optional<CredentialStoreConnector> optional = credentialStoreConnectors.stream()
                            .filter(connector -> connector.canStore(new Callback[]{callback}))
                            .findAny();

                    if (optional.isPresent()) {
                        CredentialStoreConnector connector = optional.get();
                        List<Callback> callbacks = connectorIdToCredentialsMap.get(connector
                                .getCredentialStoreConnectorId());
                        if (callbacks == null) {
                            callbacks = new ArrayList<>();
                            connectorIdToCredentialsMap.put(connector.getCredentialStoreConnectorId(), callbacks);
                        }
                        callbacks.add(callback);
                    }
                });

        return connectorIdToCredentialsMap;
    }

    private Map<String, List<Attribute>> getConnectorAttributesMap(List<Claim> claims, Map<String,
            List<MetaClaimMapping>> metaClaimMappings) {

        Map<String, List<Attribute>> connectorAttributeMap = new HashMap<>();
        claims.stream()
                .filter(Objects::nonNull)
                .forEach(claim -> {
                    metaClaimMappings.entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .forEach(entry -> {
                                Optional<MetaClaimMapping> optional = entry.getValue().stream()
                                        .filter(metaClaimMapping -> claim.getClaimUri().equals(metaClaimMapping
                                                .getMetaClaim().getClaimUri()) && claim.getDialectUri().equals
                                                (metaClaimMapping.getMetaClaim().getDialectUri()))
                                        .findFirst();
                                if (optional.isPresent()) {
                                    Attribute attribute = new Attribute();
                                    MetaClaimMapping metaClaimMapping = optional.get();
                                    attribute.setAttributeName(metaClaimMapping.getAttributeName());
                                    attribute.setAttributeValue(claim.getValue());
                                    List<Attribute> attributes = connectorAttributeMap.get(entry.getKey());
                                    if (attributes == null) {
                                        attributes = new ArrayList<>();
                                        connectorAttributeMap.put(entry.getKey(), attributes);
                                    }
                                    attributes.add(attribute);
                                }
                            });
                });
        return connectorAttributeMap;
    }

    private Map<String, Map<String, List<Attribute>>> getConnectorViseAttributesMap(
            List<Map<String, List<Attribute>>> connectorAttributesMaps) {
        Map<String, Map<String, List<Attribute>>> uuidConnectorAttributeMap = new HashMap<>();
        connectorAttributesMaps.stream()
                .forEach(map -> {
                    String uuid = IdentityUserMgtUtil.generateUUID();
                    map.entrySet().stream()
                            .forEach(entry -> {
                                Map<String, List<Attribute>> uuidAttributesMap = uuidConnectorAttributeMap.get(entry
                                        .getKey());
                                if (uuidAttributesMap == null) {
                                    uuidAttributesMap = new HashMap<>();
                                    uuidConnectorAttributeMap.put(entry.getKey(), uuidAttributesMap);
                                }
                                uuidAttributesMap.put(uuid, entry.getValue());
                            });
                });
        return uuidConnectorAttributeMap;
    }

    private Map<String, List<String>> getConnectorIdToAttributeNameMap(List<MetaClaimMapping> metaClaimMappings,
                                                                       List<MetaClaim> metaClaims) {

        Map<String, List<String>> connectorIdToAttributeNameMap = new HashMap<>();

        metaClaims.stream()
                .filter(Objects::nonNull)
                .filter(metaClaim -> isNullOrEmpty(metaClaim.getClaimUri()))
                .forEach(metaClaim -> {
                            Optional<MetaClaimMapping> optional = metaClaimMappings.stream()
                                    .filter(metaClaimMapping -> metaClaimMapping.getMetaClaim().getClaimUri()
                                            .equals(metaClaim.getClaimUri()))
                                    .findFirst();
                            if (optional.isPresent()) {
                                MetaClaimMapping metaClaimMapping = optional.get();

                                List<String> attributeNames = connectorIdToAttributeNameMap.get(metaClaimMapping
                                        .getIdentityStoreConnectorId());
                                if (attributeNames == null) {
                                    attributeNames = new ArrayList<String>();
                                    connectorIdToAttributeNameMap.put(metaClaimMapping.getIdentityStoreConnectorId(),
                                            attributeNames);
                                }
                                attributeNames.add(metaClaimMapping.getAttributeName());
                            }
                        }
                );
        return connectorIdToAttributeNameMap;
    }

//    private List<Claim> buildClaims(List<Attribute> attributes, Map<String, MetaClaim> attributeMapping) {
//
//        return attributes.stream().map(attribute -> {
//            MetaClaim metaClaim = attributeMapping.get(attribute.getAttributeName());
//            Claim claim = new Claim();
//            claim.setClaimUri(metaClaim.getClaimUri());
//            claim.setDialectUri(metaClaim.getDialectUri());
//            claim.setValue(attribute.getAttributeValue());
//            return claim;
//        }).collect(Collectors.toList());
//    }

    private void removeAddedUsersInAFailure(Domain domain, List<UserPartition> userPartitions) {

        for (UserPartition userPartition : userPartitions) {
            try {
                domain.getIdentityStoreConnectorFromId(userPartition.getConnectorId())
                        .removeAddedUsersInAFailure(Collections.singletonList(userPartition
                                .getConnectorUserId()));
            } catch (IdentityStoreConnectorException e) {
                log.error("Error occurred while removing invalid connector user ids. " + String.join(" , ",
                        userPartitions.stream().map(UserPartition::toString).collect(Collectors.toList())
                ), e);
            }
        }
    }
}
