/*
 * Copyright (C) 2026 The crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Queries the OpenCL and GLES stacks by dlopen only. Nothing here may link against libOpenCL:
// most of crDroid's device matrix does not ship one, and a DT_NEEDED entry would make the whole
// inference process fail to start on those devices rather than fall back to CPU.

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cctype>
#include <cstring>
#include <string>

#define LOG_TAG "OpenAICore.probe"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// Minimal subset of the OpenCL 1.2 ABI. Declared locally so no CL headers are needed.
using cl_int = int32_t;
using cl_uint = uint32_t;
using cl_platform_id = void*;
using cl_platform_info = uint32_t;

constexpr cl_platform_info kClPlatformName = 0x0902;
constexpr cl_platform_info kClPlatformVersion = 0x0901;
constexpr cl_platform_info kClPlatformVendor = 0x0903;
constexpr cl_int kClSuccess = 0;

using PfnGetPlatformIDs = cl_int (*)(cl_uint, cl_platform_id*, cl_uint*);
using PfnGetPlatformInfo = cl_int (*)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);

// The candidate sonames, in the order the vendor stacks actually use them.
const char* const kOpenClSonames[] = {
    "libOpenCL.so",
    "libOpenCL.so.1",
    "libGLES_mali.so",  // Mali stacks that expose CL entry points from the GLES blob.
    "libPVROCL.so",     // PowerVR.
};

struct ClHandle {
  void* lib = nullptr;
  PfnGetPlatformIDs get_platform_ids = nullptr;
  PfnGetPlatformInfo get_platform_info = nullptr;
  cl_platform_id platform = nullptr;

  ~ClHandle() {
    if (lib != nullptr) {
      dlclose(lib);
    }
  }
};

// Opens the first usable OpenCL implementation and resolves one platform id.
bool OpenFirstPlatform(ClHandle* out) {
  for (const char* soname : kOpenClSonames) {
    void* lib = dlopen(soname, RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) {
      continue;
    }
    auto ids = reinterpret_cast<PfnGetPlatformIDs>(dlsym(lib, "clGetPlatformIDs"));
    auto info = reinterpret_cast<PfnGetPlatformInfo>(dlsym(lib, "clGetPlatformInfo"));
    if (ids == nullptr || info == nullptr) {
      dlclose(lib);
      continue;
    }
    cl_platform_id platform = nullptr;
    cl_uint count = 0;
    if (ids(1, &platform, &count) != kClSuccess || count == 0 || platform == nullptr) {
      dlclose(lib);
      continue;
    }
    out->lib = lib;
    out->get_platform_ids = ids;
    out->get_platform_info = info;
    out->platform = platform;
    return true;
  }
  return false;
}

std::string PlatformString(const ClHandle& h, cl_platform_info param) {
  size_t needed = 0;
  if (h.get_platform_info(h.platform, param, 0, nullptr, &needed) != kClSuccess || needed == 0) {
    return {};
  }
  std::string buf(needed, '\0');
  if (h.get_platform_info(h.platform, param, needed, buf.data(), nullptr) != kClSuccess) {
    return {};
  }
  // The driver includes the NUL in `needed`; trim it so the Java side gets a clean string.
  const size_t nul = buf.find('\0');
  if (nul != std::string::npos) {
    buf.resize(nul);
  }
  return buf;
}

std::string ToLower(std::string s) {
  for (char& c : s) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }
  return s;
}

jstring ToJString(JNIEnv* env, const std::string& s) {
  return s.empty() ? nullptr : env->NewStringUTF(s.c_str());
}

// Brings up a pbuffer context purely to read GL_RENDERER / GL_VERSION. Tearing it straight back
// down is deliberate: this runs in the throwaway probe process, and holding a context open would
// keep the GPU driver mapped for the lifetime of the probe.
std::string GlString(GLenum name) {
  EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display == EGL_NO_DISPLAY || eglInitialize(display, nullptr, nullptr) == EGL_FALSE) {
    return {};
  }
  const EGLint config_attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                 EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                                 EGL_NONE};
  EGLConfig config;
  EGLint num_configs = 0;
  std::string result;
  if (eglChooseConfig(display, config_attrs, &config, 1, &num_configs) && num_configs > 0) {
    const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx_attrs);
    const EGLint surf_attrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, surf_attrs);
    if (context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE &&
        eglMakeCurrent(display, surface, surface, context)) {
      const auto* value = reinterpret_cast<const char*>(glGetString(name));
      if (value != nullptr) {
        result = value;
      }
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (surface != EGL_NO_SURFACE) {
      eglDestroySurface(display, surface);
    }
    if (context != EGL_NO_CONTEXT) {
      eglDestroyContext(display, context);
    }
  }
  eglTerminate(display);
  return result;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_crdroid_intelligence_probe_NativeProbe_hasOpenCl(JNIEnv*, jclass) {
  ClHandle h;
  return OpenFirstPlatform(&h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_crdroid_intelligence_probe_NativeProbe_openClPlatformName(JNIEnv* env, jclass) {
  ClHandle h;
  if (!OpenFirstPlatform(&h)) {
    return nullptr;
  }
  return ToJString(env, PlatformString(h, kClPlatformName));
}

JNIEXPORT jstring JNICALL
Java_org_crdroid_intelligence_probe_NativeProbe_openClPlatformVersion(JNIEnv* env, jclass) {
  ClHandle h;
  if (!OpenFirstPlatform(&h)) {
    return nullptr;
  }
  return ToJString(env, PlatformString(h, kClPlatformVersion));
}

JNIEXPORT jboolean JNICALL
Java_org_crdroid_intelligence_probe_NativeProbe_isAngleOpenCl(JNIEnv*, jclass) {
  ClHandle h;
  if (!OpenFirstPlatform(&h)) {
    return JNI_FALSE;
  }
  // ANGLE identifies itself in the platform name and again in the vendor string; check both,
  // because which one carries the marker has moved between ANGLE releases.
  const std::string name = ToLower(PlatformString(h, kClPlatformName));
  const std::string vendor = ToLower(PlatformString(h, kClPlatformVendor));
  const std::string version = ToLower(PlatformString(h, kClPlatformVersion));
  const bool angle = name.find("angle") != std::string::npos ||
                     vendor.find("angle") != std::string::npos ||
                     version.find("angle") != std::string::npos;
  if (angle) {
    LOGW("ANGLE-CL detected; ML Drift kernels are not expected to compile here");
  }
  return angle ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_crdroid_intelligence_probe_NativeProbe_glRenderer(JNIEnv* env, jclass) {
  return ToJString(env, GlString(GL_RENDERER));
}

JNIEXPORT jstring JNICALL
Java_org_crdroid_intelligence_probe_NativeProbe_glVersion(JNIEnv* env, jclass) {
  return ToJString(env, GlString(GL_VERSION));
}

}  // extern "C"
