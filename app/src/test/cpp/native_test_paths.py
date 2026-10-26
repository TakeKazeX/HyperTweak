"""Explicit include paths shared by standalone native regression compilers."""

def native_include_flags(root):
    return [flag for directory in (root, *(root / name for name in
            ("bridge", "resolver", "rules", "runtime", "support", "transport")))
            for flag in ("-I", str(directory))]
