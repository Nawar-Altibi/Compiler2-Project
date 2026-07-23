values = sorted([3, 1, 2])

for index, value in enumerate(values):
    print(index, value)

pairs = list(zip(values, reversed(values)))
