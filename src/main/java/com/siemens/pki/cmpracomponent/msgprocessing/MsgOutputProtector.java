/*
 *  Copyright (c) 2022 Siemens AG
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  SPDX-License-Identifier: Apache-2.0
 */
package com.siemens.pki.cmpracomponent.msgprocessing;

import static com.siemens.pki.cmpracomponent.util.NullUtil.defaultIfNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.bouncycastle.asn1.cmp.CMPCertificate;
import org.bouncycastle.asn1.cmp.PKIBody;
import org.bouncycastle.asn1.cmp.PKIMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.siemens.pki.cmpracomponent.configuration.CmpMessageInterface;
import com.siemens.pki.cmpracomponent.msggeneration.HeaderProvider;
import com.siemens.pki.cmpracomponent.msggeneration.PkiMessageGenerator;
import com.siemens.pki.cmpracomponent.persistency.PersistencyContext;
import com.siemens.pki.cmpracomponent.protection.ProtectionProvider;
import com.siemens.pki.cmpracomponent.protection.ProtectionProviderFactory;

/**
 * the {@link MsgOutputProtector} sets the right protection for outgoing
 * messages
 *
 */
public class MsgOutputProtector {

    private static final CMPCertificate[] EMPTY_CERTIFCATE_ARRAY = new CMPCertificate[0];

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MsgOutputProtector.class);

    private final CmpMessageInterface.ReprotectMode reprotectMode;

    private final ProtectionProvider protector;
    private final PersistencyContext persistencyContext;
    private final CmpMessageInterface config;

    /**
     * @param config
     *            specific configuration
     * @param persistencyContext
     *            reference to transaction specific {@link PersistencyContext}
     * @throws Exception
     *             in case of error
     */
    MsgOutputProtector(final CmpMessageInterface config,
            final PersistencyContext persistencyContext) throws Exception {
        this.persistencyContext = persistencyContext;
        this.config = config;
        protector = new ProtectionProviderFactory()
                .createProtectionProvider(config.getOutputCredentials());
        reprotectMode = config.getReprotectMode();
    }

    private synchronized PKIMessage stripRedundantExtraCerts(PKIMessage msg) {
        if (!config.getSuppressRedundantExtraCerts()
                || persistencyContext == null) {
            return msg;
        }
        final CMPCertificate[] extraCerts = msg.getExtraCerts();
        if (extraCerts == null || extraCerts.length <= 0) {
            LOGGER.debug("no extra certs, no stripping");
            return msg;
        }

        final List<CMPCertificate> extraCertsAsList =
                new LinkedList<>(Arrays.asList(extraCerts));
        final Set<CMPCertificate> alreadySentExtraCerts =
                persistencyContext.getAlreadySentExtraCerts();

        if (extraCertsAsList.removeAll(alreadySentExtraCerts)) {
            // were able to drop some extra certs
            if (LOGGER.isDebugEnabled()) {
                // avoid unnecessary string processing, if debug isn't enabled
                LOGGER.debug("drop from " + msg.getExtraCerts().length + " to "
                        + extraCertsAsList.size());
            }
            msg = new PKIMessage(msg.getHeader(), msg.getBody(),
                    msg.getProtection(),
                    extraCertsAsList.isEmpty() ? null
                            : extraCertsAsList
                                    .toArray(new CMPCertificate[extraCertsAsList
                                            .size()]));
        }
        alreadySentExtraCerts.addAll(extraCertsAsList);
        return msg;
    }

    /**
     * generate and protect a new message
     *
     * @param headerProvider
     *            header of new message
     * @param body
     *            body of new message
     * @return new message
     * @throws Exception
     *             in case of error
     */
    PKIMessage generateAndProtectMessage(final HeaderProvider headerProvider,
            final PKIBody body) throws Exception {
        return stripRedundantExtraCerts(PkiMessageGenerator
                .generateAndProtectMessage(headerProvider, protector, body));
    }

    /**
     * protect and forward a PKI message
     *
     * @param in
     *            message to forward
     * @param issuingChain
     *            trust chain of issued certificate to add to extracerts or
     *            <code>null</code>
     * @return protected message
     * @throws Exception
     *             in case of processing error
     */
    PKIMessage protectAndForwardMessage(final PKIMessage in,
            final List<CMPCertificate> issuingChain) throws Exception {
        switch (reprotectMode) {
        case reprotect:
            return stripRedundantExtraCerts(
                    PkiMessageGenerator.generateAndProtectMessage(
                            PkiMessageGenerator
                                    .buildForwardingHeaderProvider(in),
                            protector, in.getBody(), issuingChain));
        case strip:
            return PkiMessageGenerator.generateAndProtectMessage(
                    PkiMessageGenerator.buildForwardingHeaderProvider(in),
                    ProtectionProvider.NO_PROTECTION, in.getBody(),
                    issuingChain);
        case keep:
            if (in.getHeader().getProtectionAlg() == null) {
                // message protection lost during processing, reprotect
                return stripRedundantExtraCerts(
                        PkiMessageGenerator.generateAndProtectMessage(
                                PkiMessageGenerator
                                        .buildForwardingHeaderProvider(in),
                                protector, in.getBody(), issuingChain));
            }
            final CMPCertificate[] extraCerts = Stream
                    .concat(Arrays.stream(defaultIfNull(in.getExtraCerts(),
                            EMPTY_CERTIFCATE_ARRAY)),
                            defaultIfNull(issuingChain, Collections.emptyList())
                                    .stream())
                    .distinct().toArray(CMPCertificate[]::new);

            return stripRedundantExtraCerts(new PKIMessage(in.getHeader(),
                    in.getBody(), in.getProtection(), extraCerts));
        default:
            throw new IllegalArgumentException(
                    "internal error: invalid reprotectMode mode");
        }
    }
}