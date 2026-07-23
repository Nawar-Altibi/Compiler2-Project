# Expected: four TYPE_ERROR errors.
a = [1, 2] * {"key": "value"}
b = "hello" - 5
c = True + [1, 2]
d = 5 + "hello"
