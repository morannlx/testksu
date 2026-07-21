# vivo/iQOO Runtime Compatibility

KernelSU now handles vivo/iQOO compatibility automatically at runtime. Use the
normal boot patch flow on `init_boot` or `boot`, select a standard KMI, and do
not patch `vendor_boot`.

## Mechanism

1. `ksuinit` first tries to load the bundled `kernelsu.ko` normally.
2. If the kernel rejects it only because of a version magic mismatch, `ksuinit`
   reads the new kernel log records, extracts the kernel-required vermagic,
   rewrites the in-memory module `.modinfo`, and retries `init_module`.
3. The kernel module hooks arm64 `init_module` and `finit_module` directly and
   blocks the vendor module whose `.modinfo` `name=` value is exactly `vr`.

Parse failures and non-vermagic module load failures are fail-open or reported
as the original error. There is no `_vivo` LKM variant, no Manager-side vivo
switch, and no `vendor_boot` module removal flow.

## Expected Flow

```text
Manager -> Install -> Select file -> choose init_boot.img or boot.img
        -> choose any standard KMI
        -> flash patched image
        -> boot: runtime vermagic fallback and vr.ko filter run automatically
```

## Verification

After boot, kernel logs should contain lines similar to:

```text
init_module_filter: hooked init_module + finit_module
init_module_filter: blocked vr (init_module)
Replaced module vermagic with kernel-required value: "..."
```

`blocked vr` appears only if the device actually attempts to load `vr.ko`.
The vermagic replacement line appears only when the first module load requires
runtime patching.
