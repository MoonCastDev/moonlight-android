// See pyrowave_renderer.h. The instance, device, swapchain and draw code is adapted from Moonlight
// X's vulkan_renderer.cpp (GPL-3.0, at 3e6665e7).
#include "pyrowave_renderer.h"

#include <algorithm>
#include <cstring>
#include <ctime>
#include <android/log.h>

#include "shaders_spv.h"

#define LOG_TAG "MoonCast-PyroWave"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace vkr {

namespace {
    // Keep in sync with the push constant block in the shaders
    struct PushConstants {
        float uvClamp[4];
        float params[4];
        float params2[4];
        float ycbcr[4];
    };

    constexpr float kOutputPassthrough = 0.0f;
    constexpr float kOutputPqToSdr = 1.0f;
    constexpr float kSdrWhiteNits = 203.0f;

    // The newest Vulkan a PyroWave instance and device use, where they have it
    constexpr uint32_t kPyrowaveApiVersion = VK_API_VERSION_1_3;

    constexpr uint64_t kFenceTimeoutNs = 1'000'000'000;
    constexpr uint64_t kAcquireTimeoutNs = 100'000'000;  // Mailbox and immediate never wait long

    int64_t nowNs() {
        timespec ts {};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
    }

    bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
        for (const auto& ext : extensions) {
            if (strcmp(ext.extensionName, name) == 0) {
                return true;
            }
        }
        return false;
    }

    std::vector<VkExtensionProperties> deviceExtensions(VkApi& vk, VkPhysicalDevice device) {
        uint32_t count = 0;
        vk.vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr);
        std::vector<VkExtensionProperties> extensions(count);
        vk.vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data());
        return extensions;
    }

    // Quarter turns clockwise for a pre-rotation, or -1 for a transform we can't render
    int quarterTurns(VkSurfaceTransformFlagBitsKHR transform) {
        switch (transform) {
            case VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR: return 0;
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR: return 1;
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: return 2;
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: return 3;
            default: return -1;
        }
    }

    // Rendering the display's rotation ourselves keeps the layer untransformed, which display
    // hardware handles best
    VkSurfaceTransformFlagBitsKHR choosePreTransform(const VkSurfaceCapabilitiesKHR& caps) {
        if (quarterTurns(caps.currentTransform) >= 0 && (caps.supportedTransforms & caps.currentTransform)) {
            return caps.currentTransform;
        }
        if (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
            return VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        }
        return caps.currentTransform;
    }

    // The window's size in the display's natural orientation, as a pre-rotated swapchain needs
    VkExtent2D preRotatedExtent(VkExtent2D extent, VkSurfaceTransformFlagBitsKHR transform) {
        if (quarterTurns(transform) % 2 == 1) {
            std::swap(extent.width, extent.height);
        }
        return extent;
    }

    void destroyDeviceThroughInstance(VkApi& vk, VkInstance instance, VkDevice device) {
        auto destroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(vk.vkGetInstanceProcAddr(instance, "vkDestroyDevice"));
        if (destroyDevice) {
            destroyDevice(device, nullptr);
        }
    }
}

VkInstance PyrowaveRenderer::createInstance(VkApi& vk, InstanceSetup& setup, bool frontBuffer) {
    uint32_t version = 0;
    if (vk.vkEnumerateInstanceVersion(&version) != VK_SUCCESS || version < VK_API_VERSION_1_1) {
        ALOGI("Vulkan instance version too old");
        return VK_NULL_HANDLE;
    }
    // PyroWave's Vulkan backend runs on 1.1 with extensions, and uses what's core up to 1.3
    setup.apiVersion = std::min(version, kPyrowaveApiVersion);

    uint32_t count = 0;
    vk.vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vk.vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());

    setup.extensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    for (const char* name : setup.extensions) {
        if (!hasExtension(available, name)) {
            ALOGI("Instance lacks %s", name);
            return VK_NULL_HANDLE;
        }
    }

    // Needed for HDR10 swapchains
    if (hasExtension(available, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME)) {
        setup.extensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    }
    // VK_KHR_shared_presentable_image, for a front buffer, depends on it
    if (frontBuffer && hasExtension(available, VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME)) {
        setup.extensions.push_back(VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
    }

    setup.app.pApplicationName = "MoonCast";
    setup.app.pEngineName = "MoonCast";
    setup.app.apiVersion = setup.apiVersion;
    setup.info.pApplicationInfo = &setup.app;
    setup.info.enabledExtensionCount = static_cast<uint32_t>(setup.extensions.size());
    setup.info.ppEnabledExtensionNames = setup.extensions.data();

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vk.vkCreateInstance(&setup.info, nullptr, &instance);
    if (result != VK_SUCCESS) {
        ALOGE("vkCreateInstance failed: %d", result);
        return VK_NULL_HANDLE;
    }
    return instance;
}

bool PyrowaveRenderer::prepareDevice(VkApi& vk, VkPhysicalDevice device, uint32_t queueFamily, uint32_t instanceApi,
                                     DeviceSetup& setup) {
    VkPhysicalDeviceProperties props;
    vk.vkGetPhysicalDeviceProperties(device, &props);
    if (props.apiVersion < VK_API_VERSION_1_1) {
        ALOGI("%s only supports Vulkan %u.%u", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
              VK_API_VERSION_MINOR(props.apiVersion));
        return false;
    }
    if (!hasExtension(deviceExtensions(vk, device), VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        ALOGI("%s lacks %s", props.deviceName, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        return false;
    }

    setup.queue.queueFamilyIndex = queueFamily;
    setup.queue.queueCount = 1;
    setup.queue.pQueuePriorities = &setup.priority;

    // PyroWave sees what the device has enabled, so give it all the device supports
    const uint32_t apiVersion = std::min({props.apiVersion, instanceApi, kPyrowaveApiVersion});
    if (!setup.pyrowave.query(vk, device, apiVersion)) {
        ALOGI("%s can't run PyroWave", props.deviceName);
        return false;
    }
    setup.extensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    setup.extensions.insert(setup.extensions.end(), setup.pyrowave.extensions.begin(), setup.pyrowave.extensions.end());
    setup.info.pNext = &setup.pyrowave.features2;
    setup.info.queueCreateInfoCount = 1;
    setup.info.pQueueCreateInfos = &setup.queue;
    setup.info.enabledExtensionCount = static_cast<uint32_t>(setup.extensions.size());
    setup.info.ppEnabledExtensionNames = setup.extensions.data();
    return true;
}

bool PyrowaveRenderer::probe() {
    static std::once_flag once;
    static bool supported = false;

    std::call_once(once, []() {
        VkApi vk;
        if (!PyrowaveDecoder::loadLibrary() || !vk.loadGlobal()) {
            return;
        }
        InstanceSetup instanceSetup;
        VkInstance instance = createInstance(vk, instanceSetup);
        if (instance == VK_NULL_HANDLE) {
            return;
        }

        // Set PyroWave up on a device made as the renderer would make it, and have it make a
        // decoder there. That's the only sure way to know it can run.
        if (vk.loadInstance(instance)) {
            uint32_t count = 0;
            vk.vkEnumeratePhysicalDevices(instance, &count, nullptr);
            std::vector<VkPhysicalDevice> devices(count);
            vk.vkEnumeratePhysicalDevices(instance, &count, devices.data());
            for (VkPhysicalDevice physicalDevice : devices) {
                uint32_t familyCount = 0;
                vk.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, nullptr);
                std::vector<VkQueueFamilyProperties> families(familyCount);
                vk.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, families.data());
                uint32_t family = 0;
                while (family < familyCount && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                    family++;
                }

                DeviceSetup deviceSetup;
                if (family == familyCount ||
                        !prepareDevice(vk, physicalDevice, family, instanceSetup.apiVersion, deviceSetup)) {
                    continue;
                }
                VkDevice device = VK_NULL_HANDLE;
                if (vk.vkCreateDevice(physicalDevice, &deviceSetup.info, nullptr, &device) != VK_SUCCESS) {
                    continue;
                }
                if (vk.loadDevice(device)) {
                    std::mutex queueMutex;
                    PyrowaveDecoder::DeviceInfo info;
                    info.instance = instance;
                    info.physicalDevice = physicalDevice;
                    info.device = device;
                    info.instanceInfo = &instanceSetup.info;
                    info.deviceInfo = &deviceSetup.info;
                    vk.vkGetDeviceQueue(device, family, 0, &info.queue);
                    info.queueFamily = family;
                    info.queueMutex = &queueMutex;
                    info.timelineSemaphores = deviceSetup.pyrowave.timelineSemaphores;
                    supported = PyrowaveDecoder::create(vk, info, 64, 64, false) != nullptr;
                    vk.vkDeviceWaitIdle(device);
                    vk.vkDestroyDevice(device, nullptr);
                }
                else {
                    destroyDeviceThroughInstance(vk, instance, device);
                }
                if (supported) {
                    VkPhysicalDeviceProperties props;
                    vk.vkGetPhysicalDeviceProperties(physicalDevice, &props);
                    ALOGI("PyroWave can decode on %s (Vulkan %u.%u)", props.deviceName,
                          VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion));
                    break;
                }
            }
        }
        vk.vkDestroyInstance(instance, nullptr);
        if (!supported) {
            ALOGI("PyroWave can't decode on this device");
        }
    });
    return supported;
}

std::unique_ptr<PyrowaveRenderer> PyrowaveRenderer::create(ANativeWindow* window, const PyrowaveConfig& config) {
    if (!PyrowaveDecoder::loadLibrary()) {
        return nullptr;
    }
    std::unique_ptr<PyrowaveRenderer> renderer(new PyrowaveRenderer());
    if (!renderer->init(window, config)) {
        return nullptr;
    }
    return renderer;
}

bool PyrowaveRenderer::init(ANativeWindow* window, const PyrowaveConfig& config) {
    ANativeWindow_acquire(window);
    window_ = window;
    config_ = config;
    width_ = config.width;
    height_ = config.height;
    const int width = config.width;
    const int height = config.height;

    if (!vk_.loadGlobal()) {
        return false;
    }
    instance_ = createInstance(vk_, instanceSetup_, config.frontBuffer);
    if (instance_ == VK_NULL_HANDLE || !vk_.loadInstance(instance_)) {
        return false;
    }
    VkAndroidSurfaceCreateInfoKHR surfaceInfo {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    surfaceInfo.window = window_;
    VkResult result = vk_.vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_);
    if (result != VK_SUCCESS) {
        ALOGE("vkCreateAndroidSurfaceKHR failed: %d", result);
        surface_ = VK_NULL_HANDLE;
        return false;
    }
    if (!pickDevice() || !createDevice() || !createFrameResources()) {
        return false;
    }

    PyrowaveDecoder::DeviceInfo info;
    info.instance = instance_;
    info.physicalDevice = physicalDevice_;
    info.device = device_;
    info.instanceInfo = &instanceSetup_.info;
    info.deviceInfo = &deviceSetup_.info;
    info.queue = queue_;
    info.queueFamily = queueFamily_;
    info.queueMutex = &queueMutex_;
    info.timestampValidBits = timestampValidBits_;
    info.timestampPeriodNs = timestampPeriodNs_;
    info.timelineSemaphores = deviceSetup_.pyrowave.timelineSemaphores;
    if (decodeDevice_) {
        info.device = decodeDevice_;
        info.deviceInfo = &decodeDeviceSetup_.info;
        info.queue = decodeQueue_;
        info.queueMutex = &decodeQueueMutex_;
        info.timelineSemaphores = decodeDeviceSetup_.pyrowave.timelineSemaphores;
        info.renderDevice = device_;
        info.renderVk = &vk_;
    }
    decoder_ = PyrowaveDecoder::create(decodeDevice_ ? decodeVk_ : vk_, info, width, height, config.tenBit, config.chroma444);
    if (!decoder_ && decodeDevice_) {
        ALOGW("No PyroWave decoder on its own device; decoding on the render device");
        decodeVk_.vkDestroyDevice(decodeDevice_, nullptr);
        decodeDevice_ = VK_NULL_HANDLE;
        info.device = device_;
        info.deviceInfo = &deviceSetup_.info;
        info.queue = queue_;
        info.queueMutex = &queueMutex_;
        info.timelineSemaphores = deviceSetup_.pyrowave.timelineSemaphores;
        info.renderDevice = VK_NULL_HANDLE;
        info.renderVk = nullptr;
        decoder_ = PyrowaveDecoder::create(vk_, info, width, height, config.tenBit, config.chroma444);
    }
    if (!decoder_) {
        ALOGE("No PyroWave decoder for %dx%d%s%s", width, height, config.tenBit ? " 10-bit" : "",
              config.chroma444 ? " 4:4:4" : "");
        return false;
    }
    decoder_->setLostFrameMinBlocks(config.lostFrameMinBlocks);

    const VkDescriptorSetLayout setLayout = decoder_->setLayout();
    VkPushConstantRange range {VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PushConstants)};
    VkPipelineLayoutCreateInfo layoutInfo {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &setLayout;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &range;
    if (vk_.vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    shaderInfo.codeSize = sizeof(kPyrowaveVertSpv);
    shaderInfo.pCode = kPyrowaveVertSpv;
    if (vk_.vkCreateShaderModule(device_, &shaderInfo, nullptr, &vertShader_) != VK_SUCCESS) {
        return false;
    }
    shaderInfo.codeSize = sizeof(kPyrowaveFragSpv);
    shaderInfo.pCode = kPyrowaveFragSpv;
    if (vk_.vkCreateShaderModule(device_, &shaderInfo, nullptr, &fragShader_) != VK_SUCCESS) {
        return false;
    }

    // The swapchain now, so a surface that can't take one fails the start rather than the stream
    if (!ensureSwapchain()) {
        return false;
    }

    // PyroWave GPU boost: light work between decodes on a device of its own, so the GPU keeps its clock up
    if (config.gpuBoost) {
        if (!keepBusy_.start(vk_, instance_, physicalDevice_, queueFamily_, globalPriorityExt_, 500'000, 4)) {
            ALOGW("PyroWave GPU boost couldn't start");
        }
    }

    VkPhysicalDeviceProperties props;
    vk_.vkGetPhysicalDeviceProperties(physicalDevice_, &props);
    description_ = std::string(props.deviceName) + (decoder_->fragmentPath() ? ", fragment path" : ", compute path");
    ALOGI("PyroWave renderer ready: %dx%d on %s", width, height, description_.c_str());
    return true;
}

bool PyrowaveRenderer::pickDevice() {
    uint32_t count = 0;
    vk_.vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vk_.vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    for (VkPhysicalDevice device : devices) {
        uint32_t familyCount = 0;
        vk_.vkGetPhysicalDeviceQueueFamilyProperties(device, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vk_.vkGetPhysicalDeviceQueueFamilyProperties(device, &familyCount, families.data());
        for (uint32_t i = 0; i < familyCount; i++) {
            VkBool32 presentSupported = VK_FALSE;
            vk_.vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface_, &presentSupported);
            if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presentSupported) {
                physicalDevice_ = device;
                queueFamily_ = i;
                VkPhysicalDeviceProperties props;
                vk_.vkGetPhysicalDeviceProperties(device, &props);
                timestampValidBits_ = families[i].timestampValidBits;
                timestampPeriodNs_ = props.limits.timestampPeriod;
                const auto extensions = deviceExtensions(vk_, device);
                hasColorspaceExt_ = std::any_of(instanceSetup_.extensions.begin(), instanceSetup_.extensions.end(),
                        [](const char* name) { return strcmp(name, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME) == 0; });
                hasHdrMetadataExt_ = hasExtension(extensions, VK_EXT_HDR_METADATA_EXTENSION_NAME);
                hasSharedPresentExt_ = config_.frontBuffer &&
                        hasExtension(extensions, VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME) &&
                        std::any_of(instanceSetup_.extensions.begin(), instanceSetup_.extensions.end(), [](const char* name) {
                            return strcmp(name, VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME) == 0;
                        });
                hasExternalMemoryFdExt_ = hasExtension(extensions, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
                globalPriorityExt_ = hasExtension(extensions, VK_KHR_GLOBAL_PRIORITY_EXTENSION_NAME)
                        ? VK_KHR_GLOBAL_PRIORITY_EXTENSION_NAME
                        : hasExtension(extensions, VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME)
                        ? VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME : nullptr;
                ALOGI("Using %s (Vulkan %u.%u.%u), queue family %u", props.deviceName,
                      VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion),
                      VK_API_VERSION_PATCH(props.apiVersion), i);
                return true;
            }
        }
    }
    ALOGE("No Vulkan device can show the stream");
    return false;
}

bool PyrowaveRenderer::createDevice() {
    if (!prepareDevice(vk_, physicalDevice_, queueFamily_, instanceSetup_.apiVersion, deviceSetup_)) {
        return false;
    }
    if (hasHdrMetadataExt_) {
        deviceSetup_.extensions.push_back(VK_EXT_HDR_METADATA_EXTENSION_NAME);
    }
    if (hasSharedPresentExt_) {
        deviceSetup_.extensions.push_back(VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME);
    }
    else if (config_.frontBuffer) {
        ALOGW("Front buffer asked for, but the device has no shared presentable images");
    }
    const bool ownDecodeDevice = hasSharedPresentExt_ && hasExternalMemoryFdExt_;
    if (ownDecodeDevice) {
        deviceSetup_.extensions.push_back(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
    }
    deviceSetup_.info.enabledExtensionCount = static_cast<uint32_t>(deviceSetup_.extensions.size());
    deviceSetup_.info.ppEnabledExtensionNames = deviceSetup_.extensions.data();
    VkDevice device = VK_NULL_HANDLE;
    VkResult result = VK_ERROR_INITIALIZATION_FAILED;
    // With the decodes on a device of their own, the draws ask for a high priority queue (Android
    // only lets some apps have it; dropped if refused). Decodes stay at the default priority.
    VkDeviceQueueGlobalPriorityCreateInfoKHR globalPriority {VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_KHR};
    globalPriority.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_HIGH_KHR;
    if (ownDecodeDevice && globalPriorityExt_) {
        deviceSetup_.queue.pNext = &globalPriority;
        deviceSetup_.extensions.push_back(globalPriorityExt_);
        deviceSetup_.info.enabledExtensionCount = static_cast<uint32_t>(deviceSetup_.extensions.size());
        deviceSetup_.info.ppEnabledExtensionNames = deviceSetup_.extensions.data();
        result = vk_.vkCreateDevice(physicalDevice_, &deviceSetup_.info, nullptr, &device);
        highPriority_ = result == VK_SUCCESS;
        if (result != VK_SUCCESS) {
            deviceSetup_.queue.pNext = nullptr;
            deviceSetup_.extensions.pop_back();
            deviceSetup_.info.enabledExtensionCount = static_cast<uint32_t>(deviceSetup_.extensions.size());
            deviceSetup_.info.ppEnabledExtensionNames = deviceSetup_.extensions.data();
        }
    }
    if (result != VK_SUCCESS) {
        result = vk_.vkCreateDevice(physicalDevice_, &deviceSetup_.info, nullptr, &device);
    }
    if (result != VK_SUCCESS) {
        ALOGE("vkCreateDevice failed: %d", result);
        return false;
    }
    if (!vk_.loadDevice(device)) {
        destroyDeviceThroughInstance(vk_, instance_, device);
        return false;
    }
    device_ = device;
    if (!vk_.vkSetHdrMetadataEXT) {
        hasHdrMetadataExt_ = false;
    }
    vk_.vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    if (ownDecodeDevice) {
        createDecodeDevice();
    }
    return true;
}

// From Moonlight X's VulkanRenderer::createDecodeDevice()
void PyrowaveRenderer::createDecodeDevice() {
    if (!prepareDevice(vk_, physicalDevice_, queueFamily_, instanceSetup_.apiVersion, decodeDeviceSetup_)) {
        return;
    }
    decodeDeviceSetup_.extensions.push_back(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
    decodeDeviceSetup_.info.enabledExtensionCount = static_cast<uint32_t>(decodeDeviceSetup_.extensions.size());
    decodeDeviceSetup_.info.ppEnabledExtensionNames = decodeDeviceSetup_.extensions.data();
    VkDevice device = VK_NULL_HANDLE;
    const VkResult result = vk_.vkCreateDevice(physicalDevice_, &decodeDeviceSetup_.info, nullptr, &device);
    if (result != VK_SUCCESS) {
        ALOGW("Couldn't make a device for PyroWave to decode on (%d); decoding on the render device", result);
        return;
    }
    decodeVk_ = vk_;
    if (!decodeVk_.loadDevice(device)) {
        destroyDeviceThroughInstance(vk_, instance_, device);
        return;
    }
    decodeDevice_ = device;
    decodeVk_.vkGetDeviceQueue(decodeDevice_, queueFamily_, 0, &decodeQueue_);
    ALOGI("PyroWave decodes on a device of its own; rendering at %s", highPriority_ ? "high priority" : "the default priority");
}

void PyrowaveRenderer::noteGpuResult(VkResult result) {
    if (result == VK_ERROR_DEVICE_LOST) {
        // Lost for good: no point trying again
        gpuFailures_ = kMaxGpuFailures;
        ALOGE("The GPU device was lost");
    }
    else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR && result != VK_ERROR_OUT_OF_DATE_KHR &&
             result != VK_TIMEOUT && result != VK_NOT_READY) {
        gpuFailures_++;
    }
}

std::string PyrowaveRenderer::presentDescription() {
    std::lock_guard<std::mutex> lock(describeMutex_);
    std::string text = frontBuffer_ ? "front buffer"
            : presentMode_ == VK_PRESENT_MODE_IMMEDIATE_KHR ? "immediate"
            : presentMode_ == VK_PRESENT_MODE_MAILBOX_KHR ? "mailbox" : "FIFO";
    if (decodeDevice_) {
        text += ", own decode device";
    }
    return text;
}

bool PyrowaveRenderer::createFrameResources() {
    VkCommandPoolCreateInfo poolInfo {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily_;
    if (vk_.vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_) != VK_SUCCESS) {
        return false;
    }
    VkCommandBufferAllocateInfo allocInfo {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = kFramesInFlight;
    if (vk_.vkAllocateCommandBuffers(device_, &allocInfo, commandBuffers_) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < kFramesInFlight; i++) {
        VkFenceCreateInfo fenceInfo {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VkSemaphoreCreateInfo semInfo {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (vk_.vkCreateFence(device_, &fenceInfo, nullptr, &fences_[i]) != VK_SUCCESS ||
                vk_.vkCreateSemaphore(device_, &semInfo, nullptr, &imageAcquired_[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

PyrowaveRenderer::~PyrowaveRenderer() {
    keepBusy_.stop();
    if (device_) {
        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            vk_.vkDeviceWaitIdle(device_);
        }
        for (int i = 0; i < kFramesInFlight; i++) {
            releasePlanes(slotPlanes_[i]);
            slotPlanes_[i] = nullptr;
        }
        if (decodeDevice_) {
            std::lock_guard<std::mutex> lock(decodeQueueMutex_);
            decodeVk_.vkDeviceWaitIdle(decodeDevice_);
        }
        // Its planes and descriptor sets, before the device goes
        decoder_.reset();
        if (decodeDevice_) {
            decodeVk_.vkDestroyDevice(decodeDevice_, nullptr);
            decodeDevice_ = VK_NULL_HANDLE;
        }
        destroySwapchain();
        if (pipeline_) vk_.vkDestroyPipeline(device_, pipeline_, nullptr);
        if (renderPass_) vk_.vkDestroyRenderPass(device_, renderPass_, nullptr);
        if (pipelineLayout_) vk_.vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        if (vertShader_) vk_.vkDestroyShaderModule(device_, vertShader_, nullptr);
        if (fragShader_) vk_.vkDestroyShaderModule(device_, fragShader_, nullptr);
        for (int i = 0; i < kFramesInFlight; i++) {
            if (fences_[i]) vk_.vkDestroyFence(device_, fences_[i], nullptr);
            if (imageAcquired_[i]) vk_.vkDestroySemaphore(device_, imageAcquired_[i], nullptr);
        }
        if (commandPool_) vk_.vkDestroyCommandPool(device_, commandPool_, nullptr);
        vk_.vkDestroyDevice(device_, nullptr);
    }
    if (surface_) {
        vk_.vkDestroySurfaceKHR(instance_, surface_, nullptr);
    }
    if (instance_) {
        vk_.vkDestroyInstance(instance_, nullptr);
    }
    if (window_) {
        ANativeWindow_release(window_);
    }
}

void PyrowaveRenderer::releasePlanes(PyrowavePlanes* planes) {
    if (planes && decoder_) {
        decoder_->release(planes);
    }
}

void PyrowaveRenderer::setHdrMode(bool enabled, const uint8_t* metadata, size_t metadataLength) {
    // From Moonlight X's VulkanRenderer::setHdrMode()
    HdrMetadata parsed;
    if (metadata && metadataLength >= 24) {
        uint16_t v[12];
        memcpy(v, metadata, sizeof(v));
        ALOGI("HDR metadata: max %u nits, min %.4f nits, MaxCLL %u, MaxFALL %u", v[8], v[9] * 0.0001f, v[10], v[11]);
        // A 10000 nit peak is the top of the PQ range, which hosts send when they don't know the
        // display's real peak. Passed on, it has the compositor squeeze everything above the
        // panel's own peak into a sliver, so leave it out.
        if (v[8] >= 10000) {
            ALOGI("Ignoring placeholder HDR metadata");
        }
        else {
            // SS_HDR_METADATA: R, G, B primaries and white point in 0.00002 units, max display
            // luminance in nits, min in 0.0001 nits, then MaxCLL and MaxFALL in nits
            auto xy = [](uint16_t x, uint16_t y) { return VkXYColorEXT {x * 0.00002f, y * 0.00002f}; };
            parsed.vk.displayPrimaryRed = xy(v[0], v[1]);
            parsed.vk.displayPrimaryGreen = xy(v[2], v[3]);
            parsed.vk.displayPrimaryBlue = xy(v[4], v[5]);
            parsed.vk.whitePoint = xy(v[6], v[7]);
            parsed.vk.maxLuminance = v[8];
            parsed.vk.minLuminance = v[9] * 0.0001f;
            parsed.vk.maxContentLightLevel = v[10];
            parsed.vk.maxFrameAverageLightLevel = v[11];
            if (v[10] != 0) {
                parsed.contentPeakNits = v[10];
            }
            else if (v[8] != 0) {
                parsed.contentPeakNits = v[8];
            }
        }
    }
    std::lock_guard<std::mutex> lock(hdrMutex_);
    hdrEnabled_ = enabled;
    hdrMetadata_ = parsed;
    hdrChanged_ = true;
}

void PyrowaveRenderer::applyHdrMetadata() {
    if (!hasHdrMetadataExt_ || !swapchain_) {
        ALOGW("Can't pass HDR metadata to the compositor: %s", hasHdrMetadataExt_ ? "no swapchain" : "no VK_EXT_hdr_metadata");
        return;
    }
    VkHdrMetadataEXT metadata;
    {
        std::lock_guard<std::mutex> lock(hdrMutex_);
        metadata = hdrMetadata_.vk;
    }
    if (metadata.maxLuminance > 0) {
        vk_.vkSetHdrMetadataEXT(device_, 1, &swapchain_, &metadata);
        ALOGI("Applied HDR metadata: max %.0f nits, MaxCLL %.0f, MaxFALL %.0f", metadata.maxLuminance,
              metadata.maxContentLightLevel, metadata.maxFrameAverageLightLevel);
    }
    else {
        ALOGW("No HDR metadata from the host; the compositor will assume a default peak");
    }
}

PyrowaveStats PyrowaveRenderer::takeStats() {
    std::lock_guard<std::mutex> lock(statsMutex_);
    PyrowaveStats stats = stats_;
    stats_ = {};
    return stats;
}

bool PyrowaveRenderer::retireSlot(int slot) {
    VkResult result = vk_.vkWaitForFences(device_, 1, &fences_[slot], VK_TRUE, kFenceTimeoutNs);
    if (result != VK_SUCCESS) {
        ALOGE("Timed out waiting for the GPU: %d", result);
        // A render that never finishes is as bad as a lost device
        noteGpuResult(result == VK_TIMEOUT ? VK_ERROR_DEVICE_LOST : result);
        return false;
    }
    if (slotPlanes_[slot]) {
        // The render waited for the decode, so its GPU time is in
        int64_t gpuStartNs = 0;
        int64_t gpuEndNs = 0;
        if (decoder_->gpuTimes(slotReady_[slot], &gpuStartNs, &gpuEndNs) && gpuEndNs >= gpuStartNs) {
            const int64_t runStartNs = std::max(gpuStartNs, lastGpuEndNs_);
            std::lock_guard<std::mutex> lock(statsMutex_);
            stats_.decodeNs += std::max<int64_t>(gpuEndNs - runStartNs, 0);
            lastGpuEndNs_ = std::max(gpuEndNs, lastGpuEndNs_);
        }
        releasePlanes(slotPlanes_[slot]);
        slotPlanes_[slot] = nullptr;
        slotReady_[slot] = 0;
    }
    return true;
}

bool PyrowaveRenderer::submit(const uint8_t* data, size_t size, const PyrowaveGap* gaps, size_t gapCount,
                              const std::vector<size_t>* recordStarts, PyrowaveDecoder::Partial partial) {
    const auto drop = [this]() {
        std::lock_guard<std::mutex> lock(statsMutex_);
        stats_.dropped++;
        return false;
    };

    if (failed()) {
        return drop();
    }
    const int64_t startNs = nowNs();
    uint64_t readyValue = 0;
    PyrowaveDecoder::DecodeTimes times;
    PyrowavePlanes* planes = decoder_->decode(data, size, gaps, gapCount, recordStarts, partial, &readyValue, &times);
    if (!planes) {
        return drop();
    }
    {
        // The CPU's share of decoding; the GPU's is counted once the frame's render is done
        std::lock_guard<std::mutex> lock(statsMutex_);
        stats_.decodeNs += std::max<int64_t>(nowNs() - startNs - times.waitedNs, 0);
        stats_.decoded++;
    }
    if (decoder_->ownDevice()) {
        // Its timeline is on the decode device, so the render waits for it here
        const VkResult waited = decoder_->waitDecoded(readyValue, kFenceTimeoutNs);
        if (waited != VK_SUCCESS) {
            ALOGE("PyroWave decode didn't finish: %d", waited);
            releasePlanes(planes);
            return drop();
        }
    }

    float contentPeakNits;
    {
        std::lock_guard<std::mutex> lock(hdrMutex_);
        if (hdrChanged_) {
            hdrChanged_ = false;
            if (hdrActive_ != hdrEnabled_) {
                hdrActive_ = hdrEnabled_;
                // A new swapchain format and color space
                swapchainDirty_ = true;
                ALOGI("Host HDR %s", hdrActive_ ? "on" : "off");
            }
            else if (outputPq_) {
                swapchainDirty_ = true;  // New metadata for the HDR10 swapchain
            }
        }
        contentPeakNits = hdrMetadata_.contentPeakNits;
    }
    if (!ensureSwapchain()) {
        releasePlanes(planes);
        return drop();
    }

    const int slot = static_cast<int>(frameCounter_ % kFramesInFlight);
    if (!retireSlot(slot)) {
        releasePlanes(planes);
        return drop();
    }

    uint32_t imageIndex = 0;
    VkResult result = vk_.vkAcquireNextImageKHR(device_, swapchain_, acquireTimeoutNs_, imageAcquired_[slot],
                                                VK_NULL_HANDLE, &imageIndex);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        swapchainDirty_ = true;
        if (!ensureSwapchain()) {
            releasePlanes(planes);
            return drop();
        }
        result = vk_.vkAcquireNextImageKHR(device_, swapchain_, acquireTimeoutNs_, imageAcquired_[slot],
                                           VK_NULL_HANDLE, &imageIndex);
    }
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        if (result != VK_TIMEOUT && result != VK_NOT_READY) {
            ALOGE("vkAcquireNextImageKHR failed: %d", result);
            noteGpuResult(result);
            swapchainDirty_ = true;
        }
        releasePlanes(planes);
        return drop();
    }

    PushConstants pc {};
    const float w = static_cast<float>(width_);
    const float h = static_cast<float>(height_);
    // Stay half a texel inside the picture so filtering never pulls in the edge
    pc.uvClamp[0] = 0.5f / w;
    pc.uvClamp[1] = 0.5f / h;
    pc.uvClamp[2] = 1.0f - 0.5f / w;
    pc.uvClamp[3] = 1.0f - 0.5f / h;
    pc.params[0] = static_cast<float>(std::max(quarterTurns(preTransform_), 0));
    // Low dithers at the output's own precision. High dithers at 8-bit precision even on a 10-bit
    // output, which also hides banding from an 8-bit panel or compositor further on.
    pc.params[1] = config_.ditherMode == 1 ? 1.0f / static_cast<float>((1 << outputBits_) - 1)
                 : config_.ditherMode == 2 ? 1.0f / 255.0f : 0.0f;
    pc.params[2] = static_cast<float>(frameCounter_ % 64);
    pc.params[3] = (hdrActive_ && !outputPq_) ? kOutputPqToSdr : kOutputPassthrough;
    pc.params2[0] = contentPeakNits;
    pc.params2[1] = kSdrWhiteNits;
    // PyroWave is always full range, in the colorspace we asked for (0 = BT.601), or BT.2020 for HDR
    const int colorspace = hdrActive_ ? 2 : config_.colorspace;
    pc.ycbcr[0] = colorspace == 0 ? 0.299f : colorspace == 2 ? 0.2627f : 0.2126f;
    pc.ycbcr[1] = colorspace == 0 ? 0.114f : colorspace == 2 ? 0.0593f : 0.0722f;
    // Gray chroma as the host's 8-bit UNORM target stores it; 16-bit is as good as 0.5
    pc.ycbcr[2] = config_.tenBit ? 0.5f : 128.0f / 255.0f;
    // 4:4:4 has a chroma sample at every pixel
    pc.ycbcr[3] = (config_.centerChroma || config_.chroma444) ? 0.0f : 0.5f / w;

    VkCommandBuffer cmd = commandBuffers_[slot];
    vk_.vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo beginInfo {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk_.vkBeginCommandBuffer(cmd, &beginInfo);

    // Decoding on a device of its own: the decode was waited for above, and its planes are taken
    // over from that device (Moonlight X's renderFrame())
    const bool ownDecodeDevice = decoder_->ownDevice();
    VkImageMemoryBarrier planeBarriers[3] {};
    if (ownDecodeDevice) {
        for (int i = 0; i < 3; i++) {
            planeBarriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            planeBarriers[i].srcAccessMask = 0;
            planeBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            planeBarriers[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            planeBarriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            planeBarriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
            planeBarriers[i].dstQueueFamilyIndex = queueFamily_;
            planeBarriers[i].image = planes->renderImages[i];
            planeBarriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        vk_.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                 0, 0, nullptr, 0, nullptr, 3, planeBarriers);
    }
    // Without timeline semaphores nothing below waits for the decode on the GPU, but it's ahead of
    // this on the same queue: this barrier waits for it and makes its writes visible
    const bool waitDecode = !ownDecodeDevice && decoder_->timeline() != VK_NULL_HANDLE;
    if (!waitDecode && !ownDecodeDevice) {
        VkMemoryBarrier decoded {VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        decoded.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        decoded.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vk_.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                 0, 1, &decoded, 0, nullptr, 0, nullptr);
    }

    VkRenderPassBeginInfo rpBegin {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rpBegin.renderPass = renderPass_;
    rpBegin.framebuffer = framebuffers_[imageIndex];
    rpBegin.renderArea = {{0, 0}, extent_};
    vk_.vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport {0.0f, 0.0f, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0.0f, 1.0f};
    VkRect2D scissor {{0, 0}, extent_};
    vk_.vkCmdSetViewport(cmd, 0, 1, &viewport);
    vk_.vkCmdSetScissor(cmd, 0, 1, &scissor);
    vk_.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vk_.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_, 0, 1, &planes->descriptorSet,
                                0, nullptr);
    vk_.vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                           sizeof(pc), &pc);
    vk_.vkCmdDraw(cmd, 3, 1, 0, 0);
    vk_.vkCmdEndRenderPass(cmd);
    // And back to the decode device, which only gets these planes again once this render is done
    if (ownDecodeDevice) {
        for (int i = 0; i < 3; i++) {
            planeBarriers[i].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            planeBarriers[i].dstAccessMask = 0;
            planeBarriers[i].srcQueueFamilyIndex = queueFamily_;
            planeBarriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
        }
        vk_.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                                 0, 0, nullptr, 0, nullptr, 3, planeBarriers);
    }
    vk_.vkEndCommandBuffer(cmd);

    // The swapchain image, and the decode into the planes
    const VkSemaphore waitSemaphores[2] = {imageAcquired_[slot], waitDecode ? decoder_->timeline() : VK_NULL_HANDLE};
    const VkPipelineStageFlags waitStages[2] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                                                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT};
    const uint64_t waitValues[2] = {0, readyValue};
    VkTimelineSemaphoreSubmitInfo timelineInfo {VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
    timelineInfo.waitSemaphoreValueCount = 2;
    timelineInfo.pWaitSemaphoreValues = waitValues;

    VkSubmitInfo submitInfo {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.pNext = waitDecode ? &timelineInfo : nullptr;
    submitInfo.waitSemaphoreCount = waitDecode ? 2 : 1;
    submitInfo.pWaitSemaphores = waitSemaphores;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = &renderDone_[imageIndex];

    vk_.vkResetFences(device_, 1, &fences_[slot]);
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        result = vk_.vkQueueSubmit(queue_, 1, &submitInfo, fences_[slot]);
    }
    if (result != VK_SUCCESS) {
        ALOGE("vkQueueSubmit failed: %d", result);
        noteGpuResult(result);
        // Nothing will signal the fence now, so start over with a signaled one. The acquired
        // image's semaphore is still pending, so the swapchain is made again.
        vk_.vkDestroyFence(device_, fences_[slot], nullptr);
        VkFenceCreateInfo fenceInfo {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vk_.vkCreateFence(device_, &fenceInfo, nullptr, &fences_[slot]) != VK_SUCCESS) {
            fences_[slot] = VK_NULL_HANDLE;
        }
        // The acquire semaphore was signaled with nothing to wait for it: start it over too
        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            vk_.vkDeviceWaitIdle(device_);
        }
        vk_.vkDestroySemaphore(device_, imageAcquired_[slot], nullptr);
        VkSemaphoreCreateInfo semInfo {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (vk_.vkCreateSemaphore(device_, &semInfo, nullptr, &imageAcquired_[slot]) != VK_SUCCESS) {
            imageAcquired_[slot] = VK_NULL_HANDLE;
        }
        swapchainDirty_ = true;
        releasePlanes(planes);
        return drop();
    }
    slotPlanes_[slot] = planes;
    slotReady_[slot] = readyValue;
    frameCounter_++;

    VkPresentInfoKHR present {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderDone_[imageIndex];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &imageIndex;
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        result = vk_.vkQueuePresentKHR(queue_, &present);
    }
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        swapchainDirty_ = true;
    }
    else if (result == VK_SUBOPTIMAL_KHR) {
        // Android reports suboptimal when the display's rotation no longer matches our
        // pre-rotation. A new rotation or size needs a new swapchain.
        VkSurfaceCapabilitiesKHR caps;
        if (vk_.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps) == VK_SUCCESS) {
            const VkSurfaceTransformFlagBitsKHR transform = choosePreTransform(caps);
            const VkExtent2D extent = preRotatedExtent(caps.currentExtent, transform);
            if (transform != preTransform_ ||
                    (caps.currentExtent.width != UINT32_MAX &&
                     (extent.width != extent_.width || extent.height != extent_.height))) {
                swapchainDirty_ = true;
            }
        }
    }
    else if (result != VK_SUCCESS) {
        ALOGE("vkQueuePresentKHR failed: %d", result);
        noteGpuResult(result);
        swapchainDirty_ = true;
    }
    if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
        std::lock_guard<std::mutex> lock(statsMutex_);
        stats_.presented++;
        gpuFailures_ = 0;
    }
    return true;
}

bool PyrowaveRenderer::ensureSwapchain() {
    if (!swapchainDirty_ && swapchain_) {
        return true;
    }
    if (!createSwapchain()) {
        return false;
    }
    swapchainDirty_ = false;
    return true;
}

bool PyrowaveRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    if (vk_.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps) != VK_SUCCESS) {
        return false;
    }
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent.width = static_cast<uint32_t>(ANativeWindow_getWidth(window_));
        extent.height = static_cast<uint32_t>(ANativeWindow_getHeight(window_));
    }
    if (extent.width == 0 || extent.height == 0) {
        return false;
    }
    const VkSurfaceTransformFlagBitsKHR transform = choosePreTransform(caps);
    extent = preRotatedExtent(extent, transform);

    uint32_t formatCount = 0;
    vk_.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vk_.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());
    if (formats.empty()) {
        return false;
    }
    // The picture is already non-linear R'G'B', so a UNORM target takes it as it is
    auto findFormat = [&](VkFormat format, VkColorSpaceKHR colorSpace, VkSurfaceFormatKHR* out) {
        for (const auto& f : formats) {
            if (f.format == format && f.colorSpace == colorSpace) {
                *out = f;
                return true;
            }
        }
        return false;
    };
    VkSurfaceFormatKHR chosen {};
    bool found = false;
    bool pq = false;
    if (hdrActive_ && hasColorspaceExt_) {
        // The stream is already PQ-encoded BT.2020, so it passes straight through
        found = pq = findFormat(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_COLOR_SPACE_HDR10_ST2084_EXT, &chosen);
    }
    if (hdrActive_ && !found) {
        ALOGW("Surface has no HDR10 format; tone mapping HDR to SDR");
    }
    if (!found && (config_.tenBit || hdrActive_)) {
        found = findFormat(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, &chosen);
    }
    if (!found) {
        found = findFormat(VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, &chosen);
    }
    if (!found) {
        chosen = formats[0];
    }

    // Mailbox shows each frame as soon as it's drawn, replacing one not yet shown
    uint32_t modeCount = 0;
    vk_.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &modeCount, nullptr);
    std::vector<VkPresentModeKHR> modes(modeCount);
    vk_.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &modeCount, modes.data());
    const auto hasMode = [&](VkPresentModeKHR mode) { return std::find(modes.begin(), modes.end(), mode) != modes.end(); };
    // Ultra low latency: immediate (no wait for vsync, may tear), else mailbox. Lowest delay:
    // mailbox. Balanced and Smoothest: FIFO, each frame waits its turn for a vsync.
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    if (config_.ultraLowLatency && hasMode(VK_PRESENT_MODE_IMMEDIATE_KHR)) {
        presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
    }
    else if ((config_.ultraLowLatency || config_.framePacing == 0 ||
              // FIFO can only keep up when the stream is slower than the screen; at its rate or faster
              // frames would queue, so the newest wins instead
              static_cast<float>(config_.streamFps) >= config_.displayHz - 1.0f) && hasMode(VK_PRESENT_MODE_MAILBOX_KHR)) {
        presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
    }
    // FIFO's acquire waits for an image the screen has let go of: never longer than about a frame,
    // so common-c's decoder thread isn't held up (a frame that would wait longer is dropped)
    acquireTimeoutNs_ = presentMode == VK_PRESENT_MODE_FIFO_KHR
            ? static_cast<uint64_t>(1'000'000'000.0 / std::max(config_.streamFps, 1)) : kAcquireTimeoutNs;
    // Front buffer: the compositor takes the image again at every vsync, so whatever was last drawn
    // into it is shown without queueing a new buffer (frames drawn on arrival, so it may tear)
    const bool shared = hasSharedPresentExt_ &&
            std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR) != modes.end();
    if (shared) {
        presentMode = VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR;
    }
    else if (hasSharedPresentExt_) {
        ALOGW("Front buffer asked for, but the surface has no shared present mode");
    }

    // The fewest images with ultra low latency (mailbox still needs three to never wait), one more
    // for Smoothest
    uint32_t imageCount = config_.ultraLowLatency
            ? std::max(caps.minImageCount, presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? 3u : 2u)
            : std::max(caps.minImageCount, config_.framePacing == 2 ? 4u : 3u);
    if (caps.maxImageCount != 0) {
        imageCount = std::min(imageCount, caps.maxImageCount);
    }
    if (shared) {
        imageCount = 1;
    }

    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) {
        compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }
    else if (!(caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)) {
        compositeAlpha = static_cast<VkCompositeAlphaFlagBitsKHR>(
                caps.supportedCompositeAlpha & -caps.supportedCompositeAlpha);
    }

    VkSwapchainCreateInfoKHR info {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = surface_;
    info.minImageCount = imageCount;
    info.imageFormat = chosen.format;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = transform;
    info.compositeAlpha = compositeAlpha;
    info.presentMode = presentMode;
    info.clipped = VK_TRUE;
    info.oldSwapchain = swapchain_;

    // Nothing may still use the old swapchain's images when we destroy them
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        vk_.vkDeviceWaitIdle(device_);
    }
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkResult result = vk_.vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain);
    destroySwapchain();
    if (result != VK_SUCCESS && shared) {
        // The driver offered the mode but refused it: the normal swapchain from now on
        ALOGW("Front buffer swapchain refused (%d); using the normal swapchain", result);
        hasSharedPresentExt_ = false;
        return createSwapchain();
    }
    if (result != VK_SUCCESS) {
        ALOGE("vkCreateSwapchainKHR failed: %d", result);
        return false;
    }
    swapchain_ = swapchain;
    extent_ = extent;
    preTransform_ = transform;
    outputPq_ = pq;
    outputBits_ = chosen.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ? 10 : 8;
    {
        std::lock_guard<std::mutex> lock(describeMutex_);
        frontBuffer_ = shared;
        presentMode_ = presentMode;
    }
    frontBufferUnavailable_ = config_.frontBuffer && !shared;

    // A shared image stays in its own layout, in which the display reads it
    const VkImageLayout finalLayout = shared ? VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    if (renderPass_ && (renderPassFormat_ != chosen.format || renderPassFinalLayout_ != finalLayout)) {
        if (pipeline_) {
            vk_.vkDestroyPipeline(device_, pipeline_, nullptr);
            pipeline_ = VK_NULL_HANDLE;
        }
        vk_.vkDestroyRenderPass(device_, renderPass_, nullptr);
        renderPass_ = VK_NULL_HANDLE;
    }
    if (!renderPass_) {
        VkAttachmentDescription color {};
        color.format = chosen.format;
        color.samples = VK_SAMPLE_COUNT_1_BIT;
        color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;  // Every pixel is drawn
        color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color.finalLayout = finalLayout;
        VkAttachmentReference ref {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass {};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &ref;
        // Wait for the presentation engine to be done with the image before writing it
        VkSubpassDependency dependency {};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        // A shared image is acquired with nothing to wait for, so it also waits for the last frame drawn into it
        dependency.srcAccessMask = shared ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : 0;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo rpInfo {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rpInfo.attachmentCount = 1;
        rpInfo.pAttachments = &color;
        rpInfo.subpassCount = 1;
        rpInfo.pSubpasses = &subpass;
        rpInfo.dependencyCount = 1;
        rpInfo.pDependencies = &dependency;
        if (vk_.vkCreateRenderPass(device_, &rpInfo, nullptr, &renderPass_) != VK_SUCCESS) {
            ALOGE("vkCreateRenderPass failed");
            renderPass_ = VK_NULL_HANDLE;
            return false;
        }
        renderPassFormat_ = chosen.format;
        renderPassFinalLayout_ = finalLayout;
    }
    if (!pipeline_ && !createPipeline()) {
        return false;
    }

    uint32_t count = 0;
    vk_.vkGetSwapchainImagesKHR(device_, swapchain_, &count, nullptr);
    std::vector<VkImage> images(count);
    vk_.vkGetSwapchainImagesKHR(device_, swapchain_, &count, images.data());
    for (VkImage image : images) {
        VkImageViewCreateInfo viewInfo {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = chosen.format;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView view = VK_NULL_HANDLE;
        if (vk_.vkCreateImageView(device_, &viewInfo, nullptr, &view) != VK_SUCCESS) {
            return false;
        }
        swapchainViews_.push_back(view);

        VkFramebufferCreateInfo fbInfo {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbInfo.renderPass = renderPass_;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &view;
        fbInfo.width = extent.width;
        fbInfo.height = extent.height;
        fbInfo.layers = 1;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        if (vk_.vkCreateFramebuffer(device_, &fbInfo, nullptr, &framebuffer) != VK_SUCCESS) {
            return false;
        }
        framebuffers_.push_back(framebuffer);

        VkSemaphoreCreateInfo semInfo {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkSemaphore semaphore = VK_NULL_HANDLE;
        if (vk_.vkCreateSemaphore(device_, &semInfo, nullptr, &semaphore) != VK_SUCCESS) {
            return false;
        }
        renderDone_.push_back(semaphore);
    }

    if (outputPq_) {
        applyHdrMetadata();
    }

    ALOGI("Swapchain %ux%u, format %d, color space %d (%s%s), %u images, %s, pre-rotated %d degrees", extent.width,
          extent.height, chosen.format, chosen.colorSpace, outputPq_ ? "HDR10" : outputBits_ == 10 ? "10-bit SDR" : "8-bit SDR",
          (hdrActive_ && !outputPq_) ? ", tone mapped" : "", count,
          shared ? "front buffer" : presentMode == VK_PRESENT_MODE_IMMEDIATE_KHR ? "immediate"
                 : presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? "mailbox" : "FIFO",
          std::max(quarterTurns(transform), 0) * 90);
    return true;
}

void PyrowaveRenderer::destroySwapchain() {
    for (VkFramebuffer fb : framebuffers_) {
        vk_.vkDestroyFramebuffer(device_, fb, nullptr);
    }
    for (VkImageView view : swapchainViews_) {
        vk_.vkDestroyImageView(device_, view, nullptr);
    }
    for (VkSemaphore sem : renderDone_) {
        vk_.vkDestroySemaphore(device_, sem, nullptr);
    }
    framebuffers_.clear();
    swapchainViews_.clear();
    renderDone_.clear();
    if (swapchain_) {
        vk_.vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

bool PyrowaveRenderer::createPipeline() {
    VkPipelineShaderStageCreateInfo stages[2] {};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertShader_;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragShader_;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertexInput {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo inputAssembly {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewportState {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blendAttachment {};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                     VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;
    const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamicStates;

    VkGraphicsPipelineCreateInfo info {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &inputAssembly;
    info.pViewportState = &viewportState;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = pipelineLayout_;
    info.renderPass = renderPass_;
    info.subpass = 0;
    VkResult result = vk_.vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline_);
    if (result != VK_SUCCESS) {
        ALOGE("vkCreateGraphicsPipelines failed: %d", result);
        pipeline_ = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

}  // namespace vkr
