# Expected: three TYPE_MISMATCH warnings. Reassignment is valid Python.
count = 0
count = "zero"

name = "Compiler"
name = 123

for i in [1, 2, 3]:
    i = {"key": i}
