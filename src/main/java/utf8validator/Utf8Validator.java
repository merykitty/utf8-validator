package utf8validator;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;

public class Utf8Validator {
    // May run with -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK = 0 to eliminate range check
    public static boolean validate(byte[] data) {
        var species = ByteVector.SPECIES_PREFERRED;

        var error = ByteVector.zero(species);
        var prev = ByteVector.zero(species);
        var prevIncomplete = species.maskAll(false);
        // Other constants are calculated on the fly because calculation is cheap, and it reduces register pressure
        var highNibbleMask = ByteVector.broadcast(species, (byte)0b11110000);
        // Load the vector because LoadVectorNode is not considered a loop invariant yet
        var hi2Lut = HI2_LUT.lanewise(VectorOperators.XOR, 0);
        var lo1Lut = LO1_LUT.lanewise(VectorOperators.XOR, 0);
        var hi1Lut = HI1_LUT.lanewise(VectorOperators.XOR, 0);
        var incompleteCheck = INCOMPLETE_CHECK.lanewise(VectorOperators.XOR, 0);
        var slice4 = SLICE_4.lanewise(VectorOperators.XOR, 0);

        int i = 0;
        for (; i < species.loopBound(data.length); i += species.length()) {
            var input = ByteVector.fromArray(species, data, i);

            // Fast path, all bytes are positive
            var nonAsciis = input.compare(VectorOperators.LT, 0);
            if (nonAsciis.or(prevIncomplete).toLong() == 0) {
                prev = input;
                continue;
            }

            // Slow path, some bytes are negative
            // Calculate vector slices
            var prev4 = input.reinterpretAsInts()
                    .rearrange(slice4.toShuffle())
                    .withLane(0, prev.reinterpretAsInts().lane(IntVector.SPECIES_PREFERRED.length() - 1));
            var prev1 = input.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHL, Byte.SIZE)
                    .lanewise(VectorOperators.OR, prev4.lanewise(VectorOperators.LSHR, Byte.SIZE * 3))
                    .reinterpretAsBytes();
            var prev2 = input.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHL, Byte.SIZE * 2)
                    .lanewise(VectorOperators.OR, prev4.lanewise(VectorOperators.LSHR, Byte.SIZE * 2))
                    .reinterpretAsBytes();
            var prev3 = input.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHL, Byte.SIZE * 3)
                    .lanewise(VectorOperators.OR, prev4.lanewise(VectorOperators.LSHR, Byte.SIZE))
                    .reinterpretAsBytes();

            // Look up errors
            // avx do not have vector shift for bytes
            var lowNibbleMask = highNibbleMask.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHR, 4)
                    .reinterpretAsBytes();
            var hi2 = input.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHR, 4)
                    .reinterpretAsBytes()
                    .lanewise(VectorOperators.AND, lowNibbleMask);
            var lo1 = prev1.lanewise(VectorOperators.AND, lowNibbleMask);
            var hi1 = prev1.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHR, 4)
                    .reinterpretAsBytes()
                    .lanewise(VectorOperators.AND, lowNibbleMask);
            hi2 = hi2.selectFrom(hi2Lut);
            lo1 = lo1.selectFrom(lo1Lut);
            hi1 = hi1.selectFrom(hi1Lut);
            var tempError = hi2.lanewise(VectorOperators.AND, lo1)
                    .lanewise(VectorOperators.AND, hi1);

            // Mask out valid 2 consecutive continuations
            var fourBytesCheck = highNibbleMask;
            var threeBytesCheck = highNibbleMask.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHL, 1)
                    .reinterpretAsBytes()
                    .lanewise(VectorOperators.AND, highNibbleMask);
            var highestBitMask = highNibbleMask.reinterpretAsInts()
                    .lanewise(VectorOperators.LSHL, 3)
                    .reinterpretAsBytes()
                    .lanewise(VectorOperators.AND, highNibbleMask);
            var maskedErrorMask = prev2.lanewise(VectorOperators.AND, threeBytesCheck)
                    .compare(VectorOperators.EQ, threeBytesCheck)
                    .or(prev3.lanewise(VectorOperators.AND, fourBytesCheck)
                            .compare(VectorOperators.EQ, fourBytesCheck));
            var maskedError = ByteVector.zero(species).blend(highestBitMask, maskedErrorMask);

            // Body epilogue
            prevIncomplete = input.lanewise(VectorOperators.AND, incompleteCheck)
                    .compare(VectorOperators.EQ, incompleteCheck);
            error = error.lanewise(VectorOperators.OR, tempError.lanewise(VectorOperators.XOR, maskedError));
            prev = input;
        }

        // This is just the copy of the previous main loop
        var input = ByteVector.fromArray(species, data, i, species.indexInRange(i, data.length));

        // Fast path, all bytes are positive
        var nonAsciis = input.compare(VectorOperators.LT, 0);
        if (nonAsciis.or(prevIncomplete).toLong() == 0) {
            return error.compare(VectorOperators.EQ, 0).allTrue();
        }

        // Slow path, some bytes are negative
        // Calculate vector slices
        var prev4 = input.reinterpretAsInts()
                .rearrange(slice4.toShuffle())
                .withLane(0, prev.reinterpretAsInts().lane(IntVector.SPECIES_PREFERRED.length() - 1));
        var prev1 = input.reinterpretAsInts()
                .lanewise(VectorOperators.LSHL, Byte.SIZE)
                .lanewise(VectorOperators.OR, prev4.lanewise(VectorOperators.LSHR, Byte.SIZE * 3))
                .reinterpretAsBytes();
        var prev2 = input.reinterpretAsInts()
                .lanewise(VectorOperators.LSHL, Byte.SIZE * 2)
                .lanewise(VectorOperators.OR, prev4.lanewise(VectorOperators.LSHR, Byte.SIZE * 2))
                .reinterpretAsBytes();
        var prev3 = input.reinterpretAsInts()
                .lanewise(VectorOperators.LSHL, Byte.SIZE * 3)
                .lanewise(VectorOperators.OR, prev4.lanewise(VectorOperators.LSHR, Byte.SIZE))
                .reinterpretAsBytes();

        // Look up errors
        // avx do not have vector shift for bytes
        var lowNibbleMask = highNibbleMask.reinterpretAsInts()
                .lanewise(VectorOperators.LSHR, 4)
                .reinterpretAsBytes();
        var hi2 = input.reinterpretAsInts()
                .lanewise(VectorOperators.LSHR, 4)
                .reinterpretAsBytes()
                .lanewise(VectorOperators.AND, lowNibbleMask);
        var lo1 = prev1.lanewise(VectorOperators.AND, lowNibbleMask);
        var hi1 = prev1.reinterpretAsInts()
                .lanewise(VectorOperators.LSHR, 4)
                .reinterpretAsBytes()
                .lanewise(VectorOperators.AND, lowNibbleMask);
        hi2 = hi2.selectFrom(hi2Lut);
        lo1 = lo1.selectFrom(lo1Lut);
        hi1 = hi1.selectFrom(hi1Lut);
        var tempError = hi2.lanewise(VectorOperators.AND, lo1)
                .lanewise(VectorOperators.AND, hi1);

        // Mask out valid 2 consecutive continuations
        var fourBytesCheck = highNibbleMask;
        var threeBytesCheck = highNibbleMask.reinterpretAsInts()
                .lanewise(VectorOperators.LSHL, 1)
                .reinterpretAsBytes()
                .lanewise(VectorOperators.AND, highNibbleMask);
        var highestBitMask = highNibbleMask.reinterpretAsInts()
                .lanewise(VectorOperators.LSHL, 3)
                .reinterpretAsBytes()
                .lanewise(VectorOperators.AND, highNibbleMask);
        var maskedErrorMask = prev2.lanewise(VectorOperators.AND, threeBytesCheck)
                .compare(VectorOperators.EQ, threeBytesCheck)
                .or(prev3.lanewise(VectorOperators.AND, fourBytesCheck)
                        .compare(VectorOperators.EQ, fourBytesCheck));
        var maskedError = ByteVector.zero(species).blend(highestBitMask, maskedErrorMask);

        // Body epilogue
        prevIncomplete = input.lanewise(VectorOperators.AND, incompleteCheck)
                .compare(VectorOperators.EQ, incompleteCheck);
        error = error.lanewise(VectorOperators.OR, tempError.lanewise(VectorOperators.XOR, maskedError));

        return error.compare(VectorOperators.EQ, 0)
                .andNot(prevIncomplete)
                .allTrue();
    }

    private static final ByteVector HI1_LUT;
    private static final ByteVector LO1_LUT;
    private static final ByteVector HI2_LUT;
    private static final ByteVector INCOMPLETE_CHECK;
    private static final IntVector SLICE_4;

    static {
        var species = ByteVector.SPECIES_PREFERRED;
        if (species.length() < ByteVector.SPECIES_128.length()) {
            throw new AssertionError();
        }

        // 11______ 0_______
        // 11______ 11______
        byte TOO_SHORT   = 1<<0;

        // 0_______ 10______
        byte TOO_LONG    = 1<<1;

        // 11100000 100_____
        byte OVERLONG_3  = 1<<2;

        // 11101101 101_____
        byte SURROGATE   = 1<<4;

        // 1100000_ 10______
        byte OVERLONG_2  = 1<<5;

        // 10______ 10______
        byte TWO_CONTS   = (byte)(1<<7);

        // 11110100 1001____
        // 11110100 101_____
        // 11110101 1001____
        // 11110101 101_____
        // 1111011_ 1001____
        // 1111011_ 101_____
        // 11111___ 1001____
        // 11111___ 101_____
        byte TOO_LARGE   = 1<<3;

        // 11110101 1000____
        // 1111011_ 1000____
        // 11111___ 1000____
        byte TOO_LARGE_1000 = 1<<6;

        // 11110000 1000____
        byte OVERLONG_4 = 1<<6;

        byte CARRY = (byte)(TOO_SHORT | TOO_LONG | TWO_CONTS); // These all have ____ in byte 1
        byte[] data;

        // HI1_LUT
        data = new byte[]{
                TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG,
                TWO_CONTS, TWO_CONTS, TWO_CONTS, TWO_CONTS,
                (byte) (TOO_SHORT | OVERLONG_2),
                TOO_SHORT,
                (byte) (TOO_SHORT | OVERLONG_3 | SURROGATE),
                (byte) (TOO_SHORT | TOO_LARGE | TOO_LARGE_1000 | OVERLONG_4),
        };
        HI1_LUT = ByteVector.fromArray(ByteVector.SPECIES_128, data, 0)
                .convertShape(VectorOperators.Conversion.ofCast(byte.class, byte.class), species, 0)
                .reinterpretAsBytes();

        // LO1_LUT
        data = new byte[] {
                (byte) (CARRY | OVERLONG_3 | OVERLONG_2 | OVERLONG_4),
                (byte) (CARRY | OVERLONG_2),
                CARRY, CARRY,
                (byte) (CARRY | TOO_LARGE),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000 | SURROGATE),
                (byte) (CARRY | TOO_LARGE_1000),
                (byte) (CARRY | TOO_LARGE_1000),
        };
        LO1_LUT = ByteVector.fromArray(ByteVector.SPECIES_128, data, 0)
                .convertShape(VectorOperators.Conversion.ofCast(byte.class, byte.class), species, 0)
                .reinterpretAsBytes();

        // HI2_LUT
        data = new byte[] {
                TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT,
                (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | OVERLONG_3 | TOO_LARGE_1000 | OVERLONG_4),
                (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | OVERLONG_3 | TOO_LARGE),
                (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | SURROGATE  | TOO_LARGE),
                (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | SURROGATE  | TOO_LARGE),
                TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT,
        };
        HI2_LUT = ByteVector.fromArray(ByteVector.SPECIES_128, data, 0)
                .convertShape(VectorOperators.Conversion.ofCast(byte.class, byte.class), species, 0)
                .reinterpretAsBytes();

        // Incomplete check verify if the each iteration ends with incomplete sequence
        data = new byte[species.length()];
        data[species.length() - 1] = (byte)0b11000000;
        data[species.length() - 2] = (byte)0b11100000;
        data[species.length() - 3] = (byte)0b11110000;
        for (int i = species.length() - 4; i >= 0; i--) {
            data[i] = -1;
        }
        INCOMPLETE_CHECK = ByteVector.fromArray(species, data, 0);

        // Slice the vector by 4 bytes, use int since avx don't have support for byte permutation
        var ispecies = IntVector.SPECIES_PREFERRED;
        SLICE_4 = VectorShuffle.fromOp(ispecies, i -> (i - 1) & (ispecies.length() - 1))
                .toVector()
                .reinterpretAsInts();
    }
}
