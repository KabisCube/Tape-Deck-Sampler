#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <espeak-ng/speak_lib.h>

enum { OUTPUT_RATE = 48000, MAX_SECONDS = 20, PCM_CHANNELS = 1 };

typedef struct {
    short *samples;
    int count;
    int capacity;
    int overflow;
} SpeechBuffer;

static int collect(short *samples, int count, espeak_EVENT *events) {
    if (count <= 0 || samples == NULL) return 0;
    SpeechBuffer *buffer = events->user_data;
    if (count > buffer->capacity - buffer->count) {
        buffer->overflow = 1;
        return 1;
    }
    memcpy(buffer->samples + buffer->count, samples, count * sizeof(short));
    buffer->count += count;
    return 0;
}

static jshortArray fail(JNIEnv *env, const char *message) {
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception != NULL) (*env)->ThrowNew(env, exception, message);
    return NULL;
}

JNIEXPORT jshortArray JNICALL
Java_com_tapedecksampler_app_RetroSpeech_generate(JNIEnv *env, jobject instance,
                                                jstring directory, jstring text, jstring voice) {
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (path == NULL) return NULL;
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, espeakINITIALIZE_DONT_EXIT);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    if (rate <= 0) return fail(env, "Could not initialize retro speech");

    const char *name = (*env)->GetStringUTFChars(env, voice, NULL);
    if (name == NULL) { espeak_Terminate(); return NULL; }
    espeak_ERROR status = espeak_SetVoiceByName(name);
    (*env)->ReleaseStringUTFChars(env, voice, name);
    if (status != EE_OK) { espeak_Terminate(); return fail(env, "Retro voice is unavailable"); }

    SpeechBuffer buffer = { .capacity = rate * MAX_SECONDS * PCM_CHANNELS };
    buffer.samples = malloc(buffer.capacity * sizeof(short));
    if (buffer.samples == NULL) { espeak_Terminate(); return fail(env, "Not enough memory for speech"); }

    const char *input = (*env)->GetStringUTFChars(env, text, NULL);
    if (input == NULL) { free(buffer.samples); espeak_Terminate(); return NULL; }
    espeak_SetSynthCallback(collect);
    status = espeak_Synth(input, strlen(input) + 1, 0, POS_CHARACTER, 0,
                          espeakCHARS_UTF8, NULL, &buffer);
    (*env)->ReleaseStringUTFChars(env, text, input);
    espeak_Terminate();

    if (status != EE_OK || buffer.overflow || buffer.count == 0) {
        free(buffer.samples);
        return fail(env, buffer.overflow ? "Speech exceeds 20 seconds; shorten the text" : "Could not generate speech");
    }

    int frames = (int) ((long long) buffer.count * OUTPUT_RATE / rate);
    jshortArray result = (*env)->NewShortArray(env, frames);
    if (result != NULL) {
        jshort *output = (*env)->GetShortArrayElements(env, result, NULL);
        if (output != NULL) {
            for (int frame = 0; frame < frames; frame++) {
                long long position = (long long) frame * rate;
                int index = (int) (position / OUTPUT_RATE);
                int fraction = (int) (position % OUTPUT_RATE);
                int next = index + 1 < buffer.count ? index + 1 : index;
                output[frame] = (short) (buffer.samples[index] +
                    (long long) (buffer.samples[next] - buffer.samples[index]) * fraction / OUTPUT_RATE);
            }
            (*env)->ReleaseShortArrayElements(env, result, output, 0);
        }
    }
    free(buffer.samples);
    return result;
}
