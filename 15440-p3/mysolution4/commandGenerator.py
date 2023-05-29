def main(time):
    for i in range(0, 25):
        print("server number: ", i)
        seed = random.randint(100, 999)
        print("java Cloud 15440 ../lib/db1.txt " + "c-1000-" + seed + " time")

main(6)
# main(8)
# main(19)