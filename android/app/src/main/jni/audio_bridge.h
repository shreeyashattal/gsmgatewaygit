#ifndef AUDIO_BRIDGE_H
#define AUDIO_BRIDGE_H

#include <pjmedia.h>
#include <pj/types.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MAX_SIMS 2

/**
 * Initialize audio bridge for a SIM slot
 */
pj_status_t audio_bridge_init(int slot, pjmedia_endpt *med_endpt);

/**
 * Start audio bridging between GSM and SIP
 * @param slot SIM slot number (0 or 1)
 * @param local_sdp Local SDP offer/answer
 * @param remote_sdp Remote SDP offer/answer
 */
pj_status_t audio_bridge_start(int slot,
                               const pjmedia_sdp_session *local_sdp,
                               const pjmedia_sdp_session *remote_sdp);

/**
 * Stop audio bridging
 */
void audio_bridge_stop(int slot);

/**
 * Destroy audio bridge resources
 */
void audio_bridge_destroy(int slot);

#ifdef __cplusplus
}
#endif

#endif /* AUDIO_BRIDGE_H */