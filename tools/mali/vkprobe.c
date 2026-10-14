/* droiddeck-vkprobe: lists the Vulkan devices the session's loader finds and tries to open each,
 * so a session log says whether a driver sees the GPU before anything else depends on it. */
#include <stdio.h>
#include <string.h>
#define VK_NO_PROTOTYPES
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#define FN(name) static PFN_##name name
FN(vkCreateInstance); FN(vkEnumeratePhysicalDevices); FN(vkGetPhysicalDeviceProperties2);
FN(vkGetPhysicalDeviceFeatures); FN(vkEnumerateDeviceExtensionProperties); FN(vkCreateDevice);
FN(vkDestroyDevice); FN(vkDestroyInstance); FN(vkGetPhysicalDeviceFeatures2);

/* DXVK (3.x) skips a device silently unless it logs at info level, so the probe repeats its
 * suitability check (DxvkDeviceCapabilities::checkDeviceCompatibility) and names what fails. */
static int has_ext(const VkExtensionProperties *e, uint32_t n, const char *name)
{
   for (uint32_t i = 0; i < n; i++)
      if (!strcmp(e[i].extensionName, name))
         return 1;
   return 0;
}

static void dxvk_check(VkPhysicalDevice dev, const VkPhysicalDeviceProperties *props)
{
   VkPhysicalDeviceMaintenance6FeaturesKHR m6 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR };
   VkPhysicalDeviceMaintenance5FeaturesKHR m5 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES_KHR, .pNext = &m6 };
   VkPhysicalDeviceDepthClipEnableFeaturesEXT dc = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT, .pNext = &m5 };
   VkPhysicalDeviceRobustness2FeaturesEXT rb = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT, .pNext = &dc };
   VkPhysicalDeviceVulkan13Features v13 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, .pNext = &rb };
   VkPhysicalDeviceVulkan12Features v12 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, .pNext = &v13 };
   VkPhysicalDeviceVulkan11Features v11 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, .pNext = &v12 };
   VkPhysicalDeviceFeatures2 f2 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &v11 };
   vkGetPhysicalDeviceFeatures2(dev, &f2);
   const VkPhysicalDeviceFeatures *c = &f2.features;

   VkExtensionProperties ext[512];
   uint32_t ne = 512;
   vkEnumerateDeviceExtensionProperties(dev, NULL, &ne, ext);
   int robust = has_ext(ext, ne, "VK_EXT_robustness2") || has_ext(ext, ne, "VK_KHR_robustness2");

   struct { const char *name; int ok; } req[] = {
#define C(x) { #x, (int)c->x }
      C(depthBiasClamp), C(depthClamp), C(dualSrcBlend), C(fillModeNonSolid), C(fragmentStoresAndAtomics),
      C(fullDrawIndexUint32), C(geometryShader), C(imageCubeArray), C(independentBlend), C(multiDrawIndirect),
      C(multiViewport), C(occlusionQueryPrecise), C(robustBufferAccess), C(sampleRateShading), C(samplerAnisotropy),
      C(shaderClipDistance), C(shaderCullDistance), C(shaderImageGatherExtended), C(shaderInt16), C(shaderInt64),
      C(shaderSampledImageArrayDynamicIndexing), C(textureCompressionBC),
#define V(s, x) { #x, (int)s.x }
      V(v11, shaderDrawParameters), V(v11, storageBuffer16BitAccess),
      V(v12, bufferDeviceAddress), V(v12, descriptorIndexing), V(v12, storageBuffer8BitAccess),
      V(v12, descriptorBindingSampledImageUpdateAfterBind), V(v12, descriptorBindingUpdateUnusedWhilePending),
      V(v12, descriptorBindingPartiallyBound), V(v12, hostQueryReset), V(v12, runtimeDescriptorArray),
      V(v12, samplerMirrorClampToEdge), V(v12, scalarBlockLayout), V(v12, shaderInt8), V(v12, timelineSemaphore),
      V(v12, uniformBufferStandardLayout), V(v12, vulkanMemoryModel),
      V(v13, inlineUniformBlock), V(v13, computeFullSubgroups), V(v13, dynamicRendering), V(v13, maintenance4),
      V(v13, shaderDemoteToHelperInvocation), V(v13, shaderZeroInitializeWorkgroupMemory),
      V(v13, subgroupSizeControl), V(v13, synchronization2),
      { "depthClipEnable (VK_EXT_depth_clip_enable)", dc.depthClipEnable && has_ext(ext, ne, "VK_EXT_depth_clip_enable") },
      { "robustBufferAccess2 (robustness2)", rb.robustBufferAccess2 && robust },
      { "nullDescriptor (robustness2)", rb.nullDescriptor && robust },
      { "VK_KHR_load_store_op_none", has_ext(ext, ne, "VK_KHR_load_store_op_none") },
      { "maintenance5 (VK_KHR_maintenance5)", m5.maintenance5 && has_ext(ext, ne, "VK_KHR_maintenance5") },
      { "maintenance6 (VK_KHR_maintenance6)", m6.maintenance6 && has_ext(ext, ne, "VK_KHR_maintenance6") },
      { "VK_KHR_swapchain", has_ext(ext, ne, "VK_KHR_swapchain") },
      { "Vulkan 1.3", props->apiVersion >= VK_API_VERSION_1_3 },
      { "256 bytes of push constants", props->limits.maxPushConstantsSize >= 256 },
   };
   int missing = 0;
   for (unsigned i = 0; i < sizeof(req) / sizeof(req[0]); i++) {
      if (!req[i].ok) {
         printf("  dxvk: missing %s\n", req[i].name);
         missing++;
      }
   }
   if (!missing)
      printf("  dxvk: every required feature present\n");
}

int main(void)
{
   void *lib = dlopen("libvulkan.so.1", RTLD_NOW);
   if (!lib) {
      printf("no Vulkan loader: %s\n", dlerror());
      return 1;
   }
   PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
   vkCreateInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
   VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "droiddeck-vkprobe", .apiVersion = VK_API_VERSION_1_3 };
   VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
   VkInstance inst;
   VkResult r = vkCreateInstance(&ici, NULL, &inst);
   if (r != VK_SUCCESS) {
      printf("vkCreateInstance failed: %d\n", r);
      return 1;
   }
#define LOAD(name) name = (PFN_##name)gipa(inst, #name)
   LOAD(vkEnumeratePhysicalDevices); LOAD(vkGetPhysicalDeviceProperties2); LOAD(vkGetPhysicalDeviceFeatures);
   LOAD(vkEnumerateDeviceExtensionProperties); LOAD(vkCreateDevice); LOAD(vkDestroyDevice); LOAD(vkDestroyInstance);
   LOAD(vkGetPhysicalDeviceFeatures2);
   uint32_t n = 0;
   r = vkEnumeratePhysicalDevices(inst, &n, NULL);
   printf("physical devices: %u (result %d)\n", n, r);
   VkPhysicalDevice devs[8];
   if (n > 8) n = 8;
   vkEnumeratePhysicalDevices(inst, &n, devs);
   for (uint32_t i = 0; i < n; i++) {
      VkPhysicalDeviceDriverProperties drv = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
      VkPhysicalDeviceProperties2 p = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &drv };
      vkGetPhysicalDeviceProperties2(devs[i], &p);
      printf("device %u: %s, Vulkan %u.%u.%u, driver %s (%s), vendor 0x%x device 0x%x\n", i,
             p.properties.deviceName, VK_API_VERSION_MAJOR(p.properties.apiVersion),
             VK_API_VERSION_MINOR(p.properties.apiVersion), VK_API_VERSION_PATCH(p.properties.apiVersion),
             drv.driverName, drv.driverInfo, p.properties.vendorID, p.properties.deviceID);
      VkPhysicalDeviceFeatures f;
      vkGetPhysicalDeviceFeatures(devs[i], &f);
      printf("  geometryShader=%u tessellationShader=%u textureCompressionBC=%u shaderClipDistance=%u shaderCullDistance=%u multiViewport=%u depthClamp=%u\n",
             f.geometryShader, f.tessellationShader, f.textureCompressionBC, f.shaderClipDistance,
             f.shaderCullDistance, f.multiViewport, f.depthClamp);
      uint32_t ne = 0;
      vkEnumerateDeviceExtensionProperties(devs[i], NULL, &ne, NULL);
      printf("  device extensions: %u\n", ne);
      dxvk_check(devs[i], &p.properties);
      float prio = 1.0f;
      VkDeviceQueueCreateInfo q = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                    .queueFamilyIndex = 0, .queueCount = 1, .pQueuePriorities = &prio };
      VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                                 .queueCreateInfoCount = 1, .pQueueCreateInfos = &q };
      VkDevice dev;
      r = vkCreateDevice(devs[i], &dci, NULL, &dev);
      printf("  vkCreateDevice: %s (%d)\n", r == VK_SUCCESS ? "ok" : "FAILED", r);
      if (r == VK_SUCCESS) {
         PFN_vkDestroyDevice destroy = (PFN_vkDestroyDevice)gipa(inst, "vkDestroyDevice");
         destroy(dev, NULL);
      }
   }
   vkDestroyInstance(inst, NULL);

   /* A Vulkan 1.0 instance, as Wine creates its own: the loader reaches the *2 queries there by
    * their KHR names, and DXVK on Wine enumerates adapters through such an instance. */
   const char *ext = VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
   VkInstanceCreateInfo ici10 = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                  .enabledExtensionCount = 1, .ppEnabledExtensionNames = &ext };
   if (vkCreateInstance(&ici10, NULL, &inst) == VK_SUCCESS) {
      PFN_vkGetPhysicalDeviceFeatures2KHR f2khr =
         (PFN_vkGetPhysicalDeviceFeatures2KHR)gipa(inst, "vkGetPhysicalDeviceFeatures2KHR");
      LOAD(vkEnumeratePhysicalDevices); LOAD(vkDestroyInstance);
      uint32_t n10 = 1;
      VkPhysicalDevice d10;
      if (f2khr && vkEnumeratePhysicalDevices(inst, &n10, &d10) >= 0 && n10) {
         VkPhysicalDeviceFeatures2 f = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 };
         f2khr(d10, &f);
         printf("Vulkan 1.0 instance (as Wine's): textureCompressionBC=%u\n", f.features.textureCompressionBC);
      }
      vkDestroyInstance(inst, NULL);
   }
   return n ? 0 : 2;
}
