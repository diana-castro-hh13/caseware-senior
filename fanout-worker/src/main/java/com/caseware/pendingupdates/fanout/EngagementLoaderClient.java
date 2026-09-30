package com.caseware.pendingupdates.fanout;

/**
 * Our connection to the other team's service that opens an engagement and tells us its template
 * version. One call takes about 1 minute, and they can only handle a limited number at once.
 *
 * <p>Rule for whoever implements this: give up on a call before the lease in {@link FanOutConfig}
 * runs out (for example: calls take 1 minute, give up after 2, lease is 3). Report that as
 * {@link DownstreamException.Kind#TRANSIENT}. Otherwise a stuck call could still be running when
 * another worker picks up the same engagement.</p>
 */
public interface EngagementLoaderClient {

    TemplateBinding readTemplateBinding(String engagementId) throws DownstreamException;
}
