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
package com.siemens.pki.cmpracomponent.cryptoservices;

import java.net.URI;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.PKIXRevocationChecker.Option;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.siemens.pki.cmpracomponent.configuration.VerificationContext;

/**
 * Class for building a certification chain for given certificate and verifying
 * it. Relies on a set of root CA certificates and intermediate certificates
 * that will be used for building the certification chain.
 *
 */
public class TrustCredentialAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TrustCredentialAdapter.class);

    private final VerificationContext config;

    /**
     *
     * @param config
     *            specific configuration
     */
    public TrustCredentialAdapter(final VerificationContext config) {
        this.config = config;
    }

    /**
     * Attempts to build a certification chain for given certificate and to
     * verify it. Relies on a set of root CA
     * certificates (trust anchors) and a set of intermediate certificates (to
     * be used as part of the chain).
     *
     * @param cert
     *            certificate for validation
     *
     * @param additionalIntermediateCerts
     *            set of intermediate certificates, must also include the
     *            certificate for validation
     *
     * @return the validated chain without trust anchor but with cert or
     *         <code>null</code> if the validation failed
     *
     */
    @SuppressWarnings("unchecked")
    public synchronized List<? extends X509Certificate> validateCertAgainstTrust(
            final X509Certificate cert,
            final List<X509Certificate> additionalIntermediateCerts) {
        final Collection<X509Certificate> trustedCertificates =
                config.getTrustedCertificates();
        if (trustedCertificates == null) {
            return null;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("cert: " + cert.getSubjectX500Principal() + ", I: "
                    + cert.getIssuerX500Principal());
            if (additionalIntermediateCerts != null) {
                for (final X509Certificate aktCert : additionalIntermediateCerts) {
                    LOGGER.debug("inter: " + aktCert.getSubjectX500Principal()
                            + ", I: " + aktCert.getIssuerX500Principal());
                }
            }
            for (final X509Certificate aktCert : trustedCertificates) {
                LOGGER.debug("trust: " + aktCert.getSubjectX500Principal()
                        + ", I: " + aktCert.getIssuerX500Principal());
            }
        }
        try {
            if (cert.getKeyUsage() != null &&
            // digitalSignature
                    !cert.getKeyUsage()[0]
                    || !config.isLeafCertAcceptable(cert)) {
                return null;
            }
            // initial state
            java.security.Security.setProperty("ocsp.enable", "false");
            boolean revocationEnabled = false;

            if (config.isAIAsEnabled()) {
                revocationEnabled = true;
                java.security.Security.setProperty("ocsp.enable", "true");
                System.setProperty("com.sun.security.enableAIAcaIssuers",
                        "true");
            } else {
                System.setProperty("com.sun.security.enableAIAcaIssuers",
                        "false");
            }

            if (config.isCDPsEnabled()) {
                revocationEnabled = true;
                System.setProperty("com.sun.security.enableCRLDP", "true");
            } else {
                System.setProperty("com.sun.security.enableCRLDP", "false");
            }

            final Set<Object> lstCertCrlStores = new HashSet<>();

            if (additionalIntermediateCerts != null) {
                additionalIntermediateCerts.stream()
                        .filter(x -> config.isIntermediateCertAcceptable(x))
                        .filter(CertUtility::isIntermediateCertificate)
                        .forEach(x -> lstCertCrlStores.add(x));
            }

            lstCertCrlStores.addAll(config.getAdditionalCerts());

            lstCertCrlStores.add(cert);

            final Collection<X509CRL> crls = config.getCRLs();
            if (crls != null && !crls.isEmpty()) {
                revocationEnabled = true;
                lstCertCrlStores.add(crls);
            }

            final CollectionCertStoreParameters ccsp =
                    new CollectionCertStoreParameters(lstCertCrlStores);

            final CertStore store = CertStore.getInstance("Collection", ccsp,
                    CertUtility.BOUNCY_CASTLE_PROVIDER);

            final X509CertSelector targetConstraints = new X509CertSelector();
            targetConstraints.setCertificate(cert);

            final Set<TrustAnchor> trust = trustedCertificates.stream()
                    .map(trustedCert -> new TrustAnchor(trustedCert, null))
                    .collect(Collectors.toSet());

            final PKIXBuilderParameters params =
                    new PKIXBuilderParameters(trust, targetConstraints);

            params.addCertStore(store);

            final CertPathBuilder cpb = CertPathBuilder.getInstance("PKIX",
                    CertUtility.BOUNCY_CASTLE_PROVIDER);

            final PKIXRevocationChecker revChecker =
                    (PKIXRevocationChecker) cpb.getRevocationChecker();

            final EnumSet<Option> pkixRevocationCheckerOptions =
                    config.getPKIXRevocationCheckerOptions();
            if (pkixRevocationCheckerOptions != null) {
                revChecker.setOptions(pkixRevocationCheckerOptions);
            }

            final URI ocspResponder = config.getOCSPResponder();
            if (ocspResponder != null) {
                revocationEnabled = true;
                java.security.Security.setProperty("ocsp.enable", "true");
                revChecker.setOcspResponder(ocspResponder);
            }

            if (revocationEnabled) {
                params.addCertPathChecker(revChecker);
            }
            params.setRevocationEnabled(revocationEnabled);

            final PKIXCertPathBuilderResult result =
                    (PKIXCertPathBuilderResult) cpb.build(params);

            final List<? extends X509Certificate> resultChain =
                    (List<? extends X509Certificate>) result.getCertPath()
                            .getCertificates();
            if (resultChain == null) {
                return null;
            }
            for (final X509Certificate aktCert : resultChain) {
                if (aktCert.equals(cert)) {
                    continue;
                }
                if (aktCert.getKeyUsage() != null
                        // keyCertSign
                        && !aktCert.getKeyUsage()[5]
                        || !config.isIntermediateCertAcceptable(aktCert)) {
                    return null;
                }
            }
            return resultChain;
        } catch (final CertPathBuilderException certExcpt) {
            //
            // if you would like to debug the PKIX CertPathBuilder
            // add "-Djava.security.debug=certpath" to the command line
            // to get more help, use "-Djava.security.debug=help" (really)
            //
            return null;
        } catch (final InvalidAlgorithmParameterException
                | NoSuchAlgorithmException ex) {
            LOGGER.error("Exception while building certificate path:"
                    + ex.getMessage());
            return null;
        }
    }

}