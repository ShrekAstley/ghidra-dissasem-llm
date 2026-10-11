# Analyzing packed or protected binaries

`detect_packing` (and the warning in `get_program_metadata`) tells you when a binary is packed or protected
(UPX, Themida/WinLicense, VMProtect, ...). Until the image is unpacked, decompiler output, xrefs and imports are unreliable.

The tool never runs the sample. Unpacking happens in an isolated VM; only the resulting dump comes back.

1. **Detect.** Run `detect_packing`. Packers with their own unpacker (UPX, ASPack, MPRESS) can often be unpacked statically.
2. **Find the original entry point (OEP)** in the VM with a debugger: break where the stub jumps into freshly written code.
3. **Dump** the process or module at the OEP (Scylla, pe-sieve) and repair the import table.
4. **Import.** Copy the dump into the headless server's dumps folder (`<dataDir>/dumps` by default, or the 4th argument to
   `HeadlessMain`) and call `import_dump(path, image_base?, oep?)`. It replaces the current program, analyzes it, marks the OEP
   and returns the packing verdict of the dump.

`import_dump` has its own permission class (`LOAD_PROGRAM`), granted only by the headless server, and it can only read files
inside the dumps folder. Themida's virtualized functions stay virtualized after a dump.
