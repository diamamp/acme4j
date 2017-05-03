/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2017 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j.it;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Registration;
import org.shredzone.acme4j.RegistrationBuilder;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.challenge.TlsSni02Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;
import org.shredzone.acme4j.it.server.DnsServer;
import org.shredzone.acme4j.it.server.HttpServer;
import org.shredzone.acme4j.it.server.TlsSniServer;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.CertificateUtils;

/**
 * Tests a complete certificate order with different challenges.
 */
public class OrderIT extends PebbleITBase {

    private static final int TLS_SNI_PORT = 5001;
    private static final int HTTP_PORT = 5002;
    private static final int DNS_PORT = 5003;

    private static final String TEST_DOMAIN = "example.com";

    private static TlsSniServer tlsSniServer;
    private static HttpServer httpServer;
    private static DnsServer dnsServer;

    @BeforeClass
    public static void setup() {
        tlsSniServer = new TlsSniServer();
        tlsSniServer.start(TLS_SNI_PORT);

        httpServer = new HttpServer();
        httpServer.start(HTTP_PORT);

        dnsServer = new DnsServer();
        dnsServer.start(DNS_PORT);

        await().until(() -> tlsSniServer.isListening()
                        && httpServer.isListening()
                        && dnsServer.isListening());
    }

    @AfterClass
    public static void shutdown() {
        tlsSniServer.stop();
        httpServer.stop();
        dnsServer.stop();
    }

    /**
     * Test if a certificate can be ordered via tns-sni-02 challenge.
     */
    @Test
    public void testTlsSniValidation() throws Exception {
        orderCertificate(TEST_DOMAIN, auth -> {
            TlsSni02Challenge challenge = auth.findChallenge(TlsSni02Challenge.TYPE);
            assertThat(challenge, is(notNullValue()));

            KeyPair challengeKey = createKeyPair();

            X509Certificate cert = CertificateUtils.createTlsSni02Certificate(
                            challengeKey, challenge.getSubject(), challenge.getSanB());

            tlsSniServer.addCertificate(challenge.getSubject(), challengeKey.getPrivate(), cert);
            return challenge;
        });
    }

    /**
     * Test if a certificate can be ordered via http-01 challenge.
     */
    @Test
    public void testHttpValidation() throws Exception {
        orderCertificate(TEST_DOMAIN, auth -> {
            Http01Challenge challenge = auth.findChallenge(Http01Challenge.TYPE);
            assertThat(challenge, is(notNullValue()));

            httpServer.addToken(challenge.getToken(), challenge.getAuthorization());
            return challenge;
        });
    }

    /**
     * Test if a certificate can be ordered via dns-01 challenge.
     */
    @Test
    @Ignore // TODO PEBBLE: cannot query our dnsServer yet...
    public void testDnsValidation() throws Exception {
        orderCertificate(TEST_DOMAIN, auth -> {
            Dns01Challenge challenge = auth.findChallenge(Dns01Challenge.TYPE);
            assertThat(challenge, is(notNullValue()));

            dnsServer.addTxtRecord("_acme-challenge." + TEST_DOMAIN, challenge.getDigest());
            return challenge;
        });
    }

    /**
     * Runs the complete process of ordering a certificate.
     *
     * @param domain
     *            Name of the domain to order a certificate for
     * @param validator
     *            {@link Validator} that finds and prepares a {@link Challenge} for domain
     *            validation
     */
    private void orderCertificate(String domain, Validator validator) throws Exception {
        KeyPair keyPair = createKeyPair();
        Session session = new Session(pebbleURI(), keyPair);

        Registration registration = new RegistrationBuilder()
                    .agreeToTermsOfService()
                    .create(session);

        KeyPair domainKeyPair = createKeyPair();

        CSRBuilder csr = new CSRBuilder();
        csr.addDomain(domain);
        csr.sign(domainKeyPair);
        byte[] encodedCsr = csr.getEncoded();

        Instant notBefore = Instant.now();
        Instant notAfter = notBefore.plus(Duration.ofDays(20L));

        Order order = registration.orderCertificate(encodedCsr, notBefore, notAfter);
        assertThat(order.getCsr(), is(encodedCsr));
        assertThat(order.getNotBefore(), is(notBefore));
        assertThat(order.getNotAfter(), is(notAfter));
        assertThat(order.getStatus(), is(Status.PENDING));

        for (Authorization auth : order.getAuthorizations()) {
            assertThat(auth.getDomain(), is(domain));
            assertThat(auth.getStatus(), is(Status.PENDING));

            Challenge challenge = validator.prepare(auth);
            challenge.trigger();

            await()
                .pollInterval(3, SECONDS)
                .timeout(30, SECONDS)
                .conditionEvaluationListener(cond -> updateAuth(auth))
                .until(auth::getStatus, not(Status.PENDING));

            if (auth.getStatus() != Status.VALID) {
                fail("Authorization failed");
            }
        }

        order.update();

        Certificate certificate = order.getCertificate();
        X509Certificate cert = certificate.getCertificate();
        assertThat(cert, not(nullValue()));
        assertThat(cert.getNotAfter(), not(nullValue()));
        assertThat(cert.getNotBefore(), not(nullValue()));
        assertThat(cert.getSubjectX500Principal().getName(), is("CN=" + domain));
    }

    /**
     * Safely updates the authorization, catching checked exceptions.
     *
     * @param auth
     *            {@link Authorization} to update
     */
    private void updateAuth(Authorization auth) {
        try {
            auth.update();
        } catch (AcmeException ex) {
            throw new AcmeLazyLoadingException(auth, ex);
        }
    }

    @FunctionalInterface
    private static interface Validator {
        Challenge prepare(Authorization auth) throws Exception;
    }

}