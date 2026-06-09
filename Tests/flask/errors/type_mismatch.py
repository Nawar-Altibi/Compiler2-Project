# Semantic Error: TYPE_MISMATCH
# Expects: assignment value type does not match variable's existing type

count = 0
count = "zero"

name = "Compiler"
name = 123

for i in [1, 2, 3]:
    i = {"key": i}
