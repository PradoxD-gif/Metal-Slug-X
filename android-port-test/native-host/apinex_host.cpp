#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include "libretro.h"

#define LOG_TAG "APInexHost"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using set_environment_fn = void (*)(retro_environment_t);
using set_video_fn = void (*)(retro_video_refresh_t);
using set_audio_fn = void (*)(retro_audio_sample_t);
using set_audio_batch_fn = void (*)(retro_audio_sample_batch_t);
using set_input_poll_fn = void (*)(retro_input_poll_t);
using set_input_state_fn = void (*)(retro_input_state_t);
using init_fn = void (*)();
using deinit_fn = void (*)();
using get_av_info_fn = void (*)(struct retro_system_av_info *);
using load_game_fn = bool (*)(const struct retro_game_info *);
using unload_game_fn = void (*)();
using run_fn = void (*)();
using serialize_size_fn = size_t (*)();
using serialize_fn = bool (*)(void *, size_t);
using unserialize_fn = bool (*)(const void *, size_t);

static void *core_handle = nullptr;
static ANativeWindow *window_handle = nullptr;
static set_video_fn set_video = nullptr;
static init_fn core_init = nullptr;
static deinit_fn core_deinit = nullptr;
static get_av_info_fn get_av_info = nullptr;
static load_game_fn load_game = nullptr;
static unload_game_fn unload_game = nullptr;
static run_fn run_frame = nullptr;
static serialize_size_fn serialize_size = nullptr;
static serialize_fn serialize_state = nullptr;
static unserialize_fn unserialize_state = nullptr;
static volatile bool game_loaded = false;
static volatile bool emulation_running = false;
static pthread_t emulation_thread{};
static bool emulation_thread_started = false;
static enum retro_pixel_format core_pixel_format = RETRO_PIXEL_FORMAT_XRGB8888;
static volatile uint32_t input_mask = 0;
static volatile int16_t analog_state[2][2] = {{0, 0}, {0, 0}};
static double audio_sample_rate = 44100.0;
static constexpr size_t AUDIO_RING_FRAMES = 32768;
static int16_t audio_ring[AUDIO_RING_FRAMES * 2];
static size_t audio_read_frame = 0;
static size_t audio_write_frame = 0;
static size_t audio_queued_frames = 0;
static pthread_mutex_t audio_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t core_mutex = PTHREAD_MUTEX_INITIALIZER;

static void *emulation_loop(void *) {
  struct timespec next{};
  clock_gettime(CLOCK_MONOTONIC, &next);
  while (emulation_running) {
    pthread_mutex_lock(&core_mutex);
    if (game_loaded && run_frame) run_frame();
    pthread_mutex_unlock(&core_mutex);
    next.tv_nsec += 16666667L;
    if (next.tv_nsec >= 1000000000L) {
      next.tv_nsec -= 1000000000L;
      next.tv_sec += 1;
    }
    clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, nullptr);
  }
  return nullptr;
}

static void stop_emulation_thread() {
  if (!emulation_thread_started) return;
  emulation_running = false;
  pthread_join(emulation_thread, nullptr);
  emulation_thread_started = false;
}

static bool environment_callback(unsigned command, void *data) {
  if (command == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT && data) {
    core_pixel_format = *static_cast<enum retro_pixel_format *>(data);
    return true;
  }
  return false;
}

static void video_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
  if (!data || !window_handle) return;
  ANativeWindow_Buffer buffer{};
  if (ANativeWindow_lock(window_handle, &buffer, nullptr) != 0) return;
  const float scale_x = static_cast<float>(buffer.width) / static_cast<float>(width);
  const float scale_y = static_cast<float>(buffer.height) / static_cast<float>(height);
  const float scale = scale_x < scale_y ? scale_x : scale_y;
  const unsigned draw_width = static_cast<unsigned>(width * scale);
  const unsigned draw_height = static_cast<unsigned>(height * scale);
  const unsigned offset_x = (static_cast<unsigned>(buffer.width) - draw_width) / 2;
  const unsigned offset_y = (static_cast<unsigned>(buffer.height) - draw_height) / 2;
  memset(buffer.bits, 0, static_cast<size_t>(buffer.stride) * buffer.height * 4);
  for (unsigned y = 0; y < draw_height; ++y) {
    const unsigned source_y = y * height / draw_height;
    const auto *source = static_cast<const uint8_t *>(data) + source_y * pitch;
    auto *destination = static_cast<uint32_t *>(buffer.bits) + (offset_y + y) * buffer.stride + offset_x;
    for (unsigned x = 0; x < draw_width; ++x) {
      const unsigned source_x = x * width / draw_width;
      uint32_t red;
      uint32_t green;
      uint32_t blue;
      if (core_pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
        const uint16_t pixel = reinterpret_cast<const uint16_t *>(source)[source_x];
        red = ((pixel >> 11) & 31) * 255 / 31;
        green = ((pixel >> 5) & 63) * 255 / 63;
        blue = (pixel & 31) * 255 / 31;
      } else if (core_pixel_format == RETRO_PIXEL_FORMAT_0RGB1555) {
        const uint16_t pixel = reinterpret_cast<const uint16_t *>(source)[source_x];
        red = ((pixel >> 10) & 31) * 255 / 31;
        green = ((pixel >> 5) & 31) * 255 / 31;
        blue = (pixel & 31) * 255 / 31;
      } else {
        const uint32_t pixel = reinterpret_cast<const uint32_t *>(source)[source_x];
        red = (pixel >> 16) & 255;
        green = (pixel >> 8) & 255;
        blue = pixel & 255;
      }
      destination[x] = 0xff000000u | (red << 16) | (green << 8) | blue;
    }
  }
  ANativeWindow_unlockAndPost(window_handle);
}

static void queue_audio(const int16_t *data, size_t frames) {
  if (!data || frames == 0) return;
  pthread_mutex_lock(&audio_mutex);
  if (frames > AUDIO_RING_FRAMES) {
    data += (frames - AUDIO_RING_FRAMES) * 2;
    frames = AUDIO_RING_FRAMES;
  }
  const size_t overflow = audio_queued_frames + frames > AUDIO_RING_FRAMES
      ? audio_queued_frames + frames - AUDIO_RING_FRAMES : 0;
  if (overflow) {
    audio_read_frame = (audio_read_frame + overflow) % AUDIO_RING_FRAMES;
    audio_queued_frames -= overflow;
  }
  for (size_t frame = 0; frame < frames; ++frame) {
    audio_ring[audio_write_frame * 2] = data[frame * 2];
    audio_ring[audio_write_frame * 2 + 1] = data[frame * 2 + 1];
    audio_write_frame = (audio_write_frame + 1) % AUDIO_RING_FRAMES;
  }
  audio_queued_frames += frames;
  pthread_mutex_unlock(&audio_mutex);
}

static void clear_audio() {
  pthread_mutex_lock(&audio_mutex);
  audio_read_frame = 0;
  audio_write_frame = 0;
  audio_queued_frames = 0;
  pthread_mutex_unlock(&audio_mutex);
}

static void audio_callback(int16_t left, int16_t right) {
  const int16_t sample[2] = {left, right};
  queue_audio(sample, 1);
}

static size_t audio_batch_callback(const int16_t *data, size_t frames) {
  queue_audio(data, frames);
  return frames;
}
static void input_poll_callback() {}
static int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
  if (port != 0) return 0;
  if (device == RETRO_DEVICE_JOYPAD && id < 32)
    return (input_mask & (1u << id)) ? 1 : 0;
  if (device == RETRO_DEVICE_ANALOG && index < 2 && id < 2)
    return analog_state[index][id];
  return 0;
}

template <typename T>
static T load_symbol(const char *name) { return reinterpret_cast<T>(dlsym(core_handle, name)); }

static void close_core() {
  if (game_loaded && unload_game) unload_game();
  if (game_loaded && core_deinit) core_deinit();
  game_loaded = false;
  if (core_handle) dlclose(core_handle);
  core_handle = nullptr;
  set_video = nullptr;
  core_init = nullptr;
  core_deinit = nullptr;
  get_av_info = nullptr;
  load_game = nullptr;
  unload_game = nullptr;
  run_frame = nullptr;
  serialize_size = nullptr;
  serialize_state = nullptr;
  unserialize_state = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_apinex_porttest_MainActivity_nativeStart(JNIEnv *env, jobject, jstring core_path, jstring game_path, jobject surface) {
  stop_emulation_thread();
  close_core();
  const char *core_name = env->GetStringUTFChars(core_path, nullptr);
  core_handle = dlopen(core_name, RTLD_NOW | RTLD_LOCAL);
  env->ReleaseStringUTFChars(core_path, core_name);
  if (!core_handle) { LOGE("Não foi possível abrir o core: %s", dlerror()); return JNI_FALSE; }

  auto set_environment = load_symbol<set_environment_fn>("retro_set_environment");
  set_video = load_symbol<set_video_fn>("retro_set_video_refresh");
  auto set_audio = load_symbol<set_audio_fn>("retro_set_audio_sample");
  auto set_audio_batch = load_symbol<set_audio_batch_fn>("retro_set_audio_sample_batch");
  auto set_input_poll = load_symbol<set_input_poll_fn>("retro_set_input_poll");
  auto set_input_state = load_symbol<set_input_state_fn>("retro_set_input_state");
  core_init = load_symbol<init_fn>("retro_init");
  core_deinit = load_symbol<deinit_fn>("retro_deinit");
  get_av_info = load_symbol<get_av_info_fn>("retro_get_system_av_info");
  load_game = load_symbol<load_game_fn>("retro_load_game");
  unload_game = load_symbol<unload_game_fn>("retro_unload_game");
  run_frame = load_symbol<run_fn>("retro_run");
  serialize_size = load_symbol<serialize_size_fn>("retro_serialize_size");
  serialize_state = load_symbol<serialize_fn>("retro_serialize");
  unserialize_state = load_symbol<unserialize_fn>("retro_unserialize");
  if (!set_environment || !set_video || !set_audio || !set_audio_batch || !set_input_poll || !set_input_state || !core_init || !core_deinit || !load_game || !unload_game || !run_frame) {
    LOGE("O core não exporta a API libretro completa"); close_core(); return JNI_FALSE;
  }

  set_environment(environment_callback);
  set_video(video_callback);
  set_audio(audio_callback);
  set_audio_batch(audio_batch_callback);
  set_input_poll(input_poll_callback);
  set_input_state(input_state_callback);
  core_init();

  const char *game_name = env->GetStringUTFChars(game_path, nullptr);
  struct retro_game_info info{}; info.path = game_name;
  const bool loaded = load_game(&info);
  env->ReleaseStringUTFChars(game_path, game_name);
  if (!loaded) { LOGE("O core recusou a imagem"); close_core(); return JNI_FALSE; }

  clear_audio();
  input_mask = 0;
  analog_state[0][0] = analog_state[0][1] = 0;
  analog_state[1][0] = analog_state[1][1] = 0;
  audio_sample_rate = 44100.0;
  if (get_av_info) {
    struct retro_system_av_info av{};
    get_av_info(&av);
    if (av.timing.sample_rate > 0.0) audio_sample_rate = av.timing.sample_rate;
    LOGI("Vídeo base: %ux%u, áudio: %.0f Hz", av.geometry.base_width, av.geometry.base_height, audio_sample_rate);
  }
  window_handle = ANativeWindow_fromSurface(env, surface);
  if (!window_handle) { close_core(); return JNI_FALSE; }
  ANativeWindow_setBuffersGeometry(window_handle, 0, 0, WINDOW_FORMAT_RGBA_8888);
  game_loaded = true;
  emulation_running = true;
  if (pthread_create(&emulation_thread, nullptr, emulation_loop, nullptr) != 0) {
    emulation_running = false;
    game_loaded = false;
    ANativeWindow_release(window_handle);
    window_handle = nullptr;
    close_core();
    return JNI_FALSE;
  }
  emulation_thread_started = true;
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_apinex_porttest_MainActivity_nativeRunFrame(JNIEnv *, jobject) {}

extern "C" JNIEXPORT void JNICALL
Java_com_apinex_porttest_MainActivity_nativeSetInput(JNIEnv *, jobject, jint button, jboolean pressed) {
  if (button < 0 || button >= 32) return;
  const uint32_t bit = 1u << static_cast<unsigned>(button);
  if (pressed) input_mask |= bit;
  else input_mask &= ~bit;
}

extern "C" JNIEXPORT void JNICALL
Java_com_apinex_porttest_MainActivity_nativeSetAnalog(JNIEnv *, jobject, jint stick, jint x, jint y) {
  if (stick < 0 || stick >= 2) return;
  if (x < -32767) x = -32767;
  if (x > 32767) x = 32767;
  if (y < -32767) y = -32767;
  if (y > 32767) y = 32767;
  analog_state[stick][0] = static_cast<int16_t>(x);
  analog_state[stick][1] = static_cast<int16_t>(y);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_apinex_porttest_MainActivity_nativeGetAudioSampleRate(JNIEnv *, jobject) {
  return static_cast<jint>(audio_sample_rate + 0.5);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_apinex_porttest_MainActivity_nativeReadAudio(JNIEnv *env, jobject, jshortArray output, jint max_frames) {
  if (!output || max_frames <= 0) return 0;
  const jsize capacity = env->GetArrayLength(output);
  const size_t requested_samples = static_cast<size_t>(max_frames) * 2;
  const size_t max_samples = requested_samples < static_cast<size_t>(capacity)
      ? requested_samples : static_cast<size_t>(capacity);
  if (max_samples < 2) return 0;
  const size_t max_read_frames = max_samples / 2;
  jshort *destination = env->GetShortArrayElements(output, nullptr);
  if (!destination) return 0;
  pthread_mutex_lock(&audio_mutex);
  const size_t frames = audio_queued_frames < max_read_frames ? audio_queued_frames : max_read_frames;
  for (size_t frame = 0; frame < frames; ++frame) {
    destination[frame * 2] = audio_ring[audio_read_frame * 2];
    destination[frame * 2 + 1] = audio_ring[audio_read_frame * 2 + 1];
    audio_read_frame = (audio_read_frame + 1) % AUDIO_RING_FRAMES;
  }
  audio_queued_frames -= frames;
  pthread_mutex_unlock(&audio_mutex);
  env->ReleaseShortArrayElements(output, destination, 0);
  return static_cast<jint>(frames);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_apinex_porttest_MainActivity_nativeSaveState(JNIEnv *env, jobject, jstring path) {
  if (!path || !serialize_size || !serialize_state) return JNI_FALSE;
  const char *file_path = env->GetStringUTFChars(path, nullptr);
  if (!file_path) return JNI_FALSE;
  bool ok = false;
  pthread_mutex_lock(&core_mutex);
  const size_t size = serialize_size();
  void *buffer = size ? malloc(size) : nullptr;
  if (buffer && serialize_state(buffer, size)) {
    FILE *file = fopen(file_path, "wb");
    if (file) {
      ok = fwrite(buffer, 1, size, file) == size;
      fclose(file);
    }
  }
  free(buffer);
  pthread_mutex_unlock(&core_mutex);
  env->ReleaseStringUTFChars(path, file_path);
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_apinex_porttest_MainActivity_nativeLoadState(JNIEnv *env, jobject, jstring path) {
  if (!path || !unserialize_state) return JNI_FALSE;
  const char *file_path = env->GetStringUTFChars(path, nullptr);
  if (!file_path) return JNI_FALSE;
  bool ok = false;
  FILE *file = fopen(file_path, "rb");
  if (file) {
    fseek(file, 0, SEEK_END);
    const long length = ftell(file);
    fseek(file, 0, SEEK_SET);
    const size_t expected = serialize_size ? serialize_size() : 0;
    if (length > 0 && (expected == 0 || static_cast<size_t>(length) == expected)) {
      void *buffer = malloc(static_cast<size_t>(length));
      if (buffer && fread(buffer, 1, static_cast<size_t>(length), file) == static_cast<size_t>(length)) {
        pthread_mutex_lock(&core_mutex);
        ok = unserialize_state(buffer, static_cast<size_t>(length));
        pthread_mutex_unlock(&core_mutex);
      }
      free(buffer);
    }
    fclose(file);
  }
  env->ReleaseStringUTFChars(path, file_path);
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_apinex_porttest_MainActivity_nativeStop(JNIEnv *, jobject) {
  stop_emulation_thread();
  if (window_handle) { ANativeWindow_release(window_handle); window_handle = nullptr; }
  input_mask = 0;
  analog_state[0][0] = analog_state[0][1] = 0;
  analog_state[1][0] = analog_state[1][1] = 0;
  clear_audio();
  close_core();
}
