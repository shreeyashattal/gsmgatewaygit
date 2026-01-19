/*
 * audio_bridge.c - Simple audio bridge for GSM<->SIP
 * Uses Android AudioRecord/AudioTrack with root-level routing
 */

 #include "audio_bridge.h"
 #include <pjlib.h>
 #include <pjmedia.h>
 #include <jni.h>
 #include <android/log.h>
 #include <stdlib.h>
 #include <unistd.h>
 
 #define THIS_FILE "audio_bridge"
 #define MAX_SIMS 2
 #define LOG_TAG "AudioBridge"
 #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
 #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
 
 /* Audio bridge state */
 typedef struct {
     pj_bool_t active;
     int slot;
     pjmedia_endpt *med_endpt;
     pj_pool_t *pool;
 } audio_bridge_state;
 
 static audio_bridge_state bridges[MAX_SIMS];
 
 /*
  * Initialize audio bridge for a slot
  */
 pj_status_t audio_bridge_init(int slot, pjmedia_endpt *med_endpt) {
     audio_bridge_state *bridge = &bridges[slot];
     
     if (slot < 0 || slot >= MAX_SIMS) {
         return PJ_EINVAL;
     }
     
     pj_bzero(bridge, sizeof(*bridge));
     bridge->slot = slot;
     bridge->med_endpt = med_endpt;
     
     /* Create memory pool */
     bridge->pool = pjmedia_endpt_create_pool(med_endpt, "audio_bridge",
                                              4000, 4000);
     if (!bridge->pool) {
         return PJ_ENOMEM;
     }
     
     LOGI("Audio bridge initialized for slot %d", slot);
     PJ_LOG(3, (THIS_FILE, "Audio bridge initialized for slot %d", slot));
     return PJ_SUCCESS;
 }
 
 /*
  * Execute root command to configure audio routing
  */
 static int exec_root_cmd(const char *cmd) {
     char full_cmd[512];
     int ret;
     
     snprintf(full_cmd, sizeof(full_cmd), "su -c \"%s\"", cmd);
     LOGI("Executing: %s", full_cmd);
     
     ret = system(full_cmd);
     
     if (ret != 0) {
         LOGE("Command failed: %s (ret=%d)", full_cmd, ret);
         return PJ_EUNKNOWN; // Indicate failure
     }
     
     return PJ_SUCCESS; // Indicate success
 }
 
 /*
  * Configure audio routing for SM6150
  */
 static pj_status_t configure_sm6150_audio(pj_bool_t enable) {
     LOGI("Configuring SM6150 audio routing: %s", enable ? "ENABLE" : "DISABLE");
     
     if (enable) {
         /* Enable voice call audio path */
         if (exec_root_cmd("tinymix 'Voice Rx Device Mute' 0 0 0") != PJ_SUCCESS) return PJ_EUNKNOWN;
         if (exec_root_cmd("tinymix 'Voice Tx Device Mute' 0 0 0") != PJ_SUCCESS) return PJ_EUNKNOWN;
         if (exec_root_cmd("tinymix 'Voice Tx Mute' 0 0 0") != PJ_SUCCESS) return PJ_EUNKNOWN;
          
         /* Set voice gains */
         if (exec_root_cmd("tinymix 'Voice Rx Gain' 20 20 20") != PJ_SUCCESS) return PJ_EUNKNOWN;
          
         /* Enable HD Voice */
         if (exec_root_cmd("tinymix 'HD Voice Enable' 1 1") != PJ_SUCCESS) return PJ_EUNKNOWN;
          
         /* Enable speaker mode for loopback */
         // Check if this command works and if it's necessary/correct for SM678
         // This command can vary significantly across Android versions/devices
         if (exec_root_cmd("service call audio 8 i32 1") != PJ_SUCCESS) {
             LOGE("Failed to set speakerphone on via service call audio 8 i32 1");
             // Not necessarily fatal, can proceed but log the error
         }
           
         /* Set audio mode to in-call */
         // Check if this command works and if it's necessary/correct for SM678
         if (exec_root_cmd("service call audio 28 i32 2") != PJ_SUCCESS) {
             LOGE("Failed to set audio mode to in-call via service call audio 28 i32 2");
             // Not necessarily fatal, can proceed but log the error
         }
          
         LOGI("Audio routing enabled");
     } else {
         /* Restore normal audio mode */
         if (exec_root_cmd("service call audio 28 i32 0") != PJ_SUCCESS) return PJ_EUNKNOWN;
         if (exec_root_cmd("service call audio 8 i32 0") != PJ_SUCCESS) return PJ_EUNKNOWN;
          
         /* Reset voice settings */
         // Note: -1 might not be a valid "reset" value for all tinymix controls.
         // It's often better to explicitly set them to a known "off" or default state.
         // For now, keep as is, but this might need further investigation.
         if (exec_root_cmd("tinymix 'Voice Rx Device Mute' -1 -1 -1") != PJ_SUCCESS) return PJ_EUNKNOWN;
         if (exec_root_cmd("tinymix 'Voice Tx Device Mute' -1 -1 -1") != PJ_SUCCESS) return PJ_EUNKNOWN;
         
         LOGI("Audio routing disabled");
     }
     
     return PJ_SUCCESS;
 }
 
 /*
  * Start audio bridge
  */
 pj_status_t audio_bridge_start(int slot,
                                const pjmedia_sdp_session *local_sdp,
                                const pjmedia_sdp_session *remote_sdp) {
     audio_bridge_state *bridge = &bridges[slot];
     
     if (slot < 0 || slot >= MAX_SIMS) {
         return PJ_EINVAL;
     }
     
     if (bridge->active) {
         LOGI("Audio bridge already active for slot %d", slot);
         return PJ_SUCCESS;
     }
     
     LOGI("========================================");
     LOGI("STARTING AUDIO BRIDGE FOR SLOT %d", slot);
     LOGI("========================================");
     
    /* Configure audio routing using root */
    pj_status_t audio_status = configure_sm6150_audio(PJ_TRUE);
    if (audio_status != PJ_SUCCESS) {
        LOGE("Failed to configure audio routing: %d", audio_status);
        return audio_status;
    }

    bridge->active = PJ_TRUE;
     
     LOGI("========================================");
     LOGI("AUDIO BRIDGE ACTIVE FOR SLOT %d", slot);
     LOGI("========================================");
     LOGI("Audio is now bridged between GSM and SIP");
     LOGI("GSM call audio will play through speaker");
     LOGI("SIP audio will be captured from microphone");
     
     PJ_LOG(3, (THIS_FILE, "Audio bridge started for slot %d", slot));
     return PJ_SUCCESS;
 }
 
 /*
  * Stop audio bridge
  */
 void audio_bridge_stop(int slot) {
     audio_bridge_state *bridge;
     
     if (slot < 0 || slot >= MAX_SIMS) {
         return;
     }
     
     bridge = &bridges[slot];
     
     if (!bridge->active) {
         return;
     }
     
     LOGI("Stopping audio bridge for slot %d", slot);
     
     /* Restore normal audio routing */
     configure_sm6150_audio(PJ_FALSE);
     
     bridge->active = PJ_FALSE;
     
     LOGI("Audio bridge stopped for slot %d", slot);
     PJ_LOG(3, (THIS_FILE, "Audio bridge stopped for slot %d", slot));
 }
 
 /*
  * Destroy audio bridge
  */
void audio_bridge_destroy(int slot) {
    audio_bridge_state *bridge;

    if (slot < 0 || slot >= MAX_SIMS) {
        return;
    }

    bridge = &bridges[slot];

    audio_bridge_stop(slot);

    if (bridge->pool) {
        pj_pool_release(bridge->pool);
        bridge->pool = NULL;
    }

    /* Reset bridge state completely */
    pj_bzero(bridge, sizeof(*bridge));

    LOGI("Audio bridge destroyed for slot %d", slot);
    PJ_LOG(3, (THIS_FILE, "Audio bridge destroyed for slot %d", slot));
}
 
 /* JNI stubs for Android audio routing */
 
 JNIEXPORT void JNICALL
 Java_com_shreeyash_gateway_AudioBridge_onGsmAudioCaptured(
     JNIEnv *env, jobject obj, jint slot, jshortArray samples) {
     /* Not used in root-level routing approach */
 }
 
 JNIEXPORT jint JNICALL
 Java_com_shreeyash_gateway_AudioBridge_getGsmAudioSamples(
     JNIEnv *env, jobject obj, jint slot, jshortArray samples) {
     /* Not used in root-level routing approach */
     return 0;
 }