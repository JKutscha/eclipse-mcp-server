package com.vogella.eclipse.mcp.p2.internal;

import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.UIServices;

/**
 * Answers p2's trust and credential prompts without a human.
 * <p>
 * The IDE's own {@code UIServices} raises a modal dialog, which blocks the
 * provisioning job until somebody clicks it. From a client that is
 * indistinguishable from a slow download, so an unattended update hangs until
 * the call times out and the real cause never surfaces. This answers instead.
 * <p>
 * Unsigned content is accepted by default on this path, because an install the
 * server performs is unattended by definition and there is nobody to click the
 * dialog. This is not bounded by which sites are configured, because a client can
 * configure a new one through eclipse_add_repository. Whatever was accepted
 * is reported, so a trusted install is auditable rather than silent.
 */
final class HeadlessTrust extends UIServices {

	private final boolean trustUnsigned;

	private final List<String> prompts = new ArrayList<>();

	private volatile boolean prompted;

	HeadlessTrust(boolean trustUnsigned) {
		this.trustUnsigned = trustUnsigned;
	}

	boolean prompted() {
		return prompted;
	}

	/** What p2 asked about, whether it was then trusted or refused. */
	synchronized List<String> prompts() {
		return List.copyOf(prompts);
	}

	/**
	 * The PGP flavour of this callback is concrete on {@link UIServices} and delegates
	 * here, so overriding it as well would only pull a restricted BouncyCastle type
	 * into this bundle.
	 */
	@Override
	public TrustInfo getTrustInfo(Certificate[][] untrustedChains, String[] unsignedDetail) {
		return trust(untrustedChains, unsignedDetail);
	}

	private TrustInfo trust(Certificate[][] untrustedChains, String[] unsignedDetail) {
		prompted = true;
		synchronized (this) {
			if (unsignedDetail != null) {
				for (String detail : unsignedDetail) {
					prompts.add(detail);
				}
			}
			if (untrustedChains != null && untrustedChains.length > 0) {
				prompts.add("%d artifact(s) signed by a certificate this IDE does not trust"
						.formatted(untrustedChains.length));
			}
		}
		// trust no certificate and persist nothing: the opt-in covers unsigned content
		// for this one call only, and must not quietly widen what the IDE trusts
		return new TrustInfo(new Certificate[0], false, trustUnsigned);
	}

	@Override
	public AuthenticationInfo getUsernamePassword(String location) {
		prompted = true;
		synchronized (this) {
			prompts.add("credentials for " + location);
		}
		return AUTHENTICATION_PROMPT_CANCELED;
	}

	@Override
	public AuthenticationInfo getUsernamePassword(String location, AuthenticationInfo previous) {
		return getUsernamePassword(location);
	}

	/** Installs this in place of the IDE's dialogs, returning what was there before. */
	static Object install(IProvisioningAgent agent, HeadlessTrust trust) {
		Object previous = agent.getService(UIServices.SERVICE_NAME);
		agent.registerService(UIServices.SERVICE_NAME, trust);
		return previous;
	}

	/** Puts the IDE's own dialogs back, so interactive updates still prompt. */
	static void restore(IProvisioningAgent agent, Object previous) {
		agent.unregisterService(UIServices.SERVICE_NAME, agent.getService(UIServices.SERVICE_NAME));
		if (previous != null) {
			agent.registerService(UIServices.SERVICE_NAME, previous);
		}
	}
}
