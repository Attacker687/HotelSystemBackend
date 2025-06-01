package com.winniethepooh.hotelsystembackend.exception;

import com.winniethepooh.hotelsystembackend.entity.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Result otherHandler(Exception e) {
        e.printStackTrace();
        return Result.error("操作失败，请联系管理员");
    }

    @ExceptionHandler(UserNameInvalidException.class)
    public Result UserNameInvalidHandler(UserNameInvalidException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UserIDCardInvalidException.class)
    public Result UserIDCardInvalidHandler(UserIDCardInvalidException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UserPhoneInvalidException.class)
    public Result UserPhoneInvalidHandler(UserPhoneInvalidException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UserEmailInvalidException.class)
    public Result UserEmailInvalidHandler(UserEmailInvalidException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UserPasswordInvalidException.class)
    public Result UserPasswordInvalidHandler(UserPasswordInvalidException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UserDuplicatedException.class)
    public Result UserDuplicatedHandler(UserDuplicatedException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public Result UserNotFoundHandler(UserNotFoundException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(PasswordIncorrectException.class)
    public Result PasswordIncorrectHandler(PasswordIncorrectException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UnknownException.class)
    public Result UnknownSituationHandler(UnknownException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UnknownOrderTypeException.class)
    public Result UnknownOrderTypeExceptionHandler(UnknownOrderTypeException exception)  {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(RoomNumberDuplicatedException.class)
    public Result RoomNumberDuplicatedExceptionHandler(RoomNumberDuplicatedException exception)  {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(UnknownRoomTypeException.class)
    public Result UnknownRoomTypeExceptionHandler(UnknownRoomTypeException exception)  {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(MismatchException.class)
    public Result MismatchExceptionHandler(MismatchException exception)  {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler(CategoryNameDuplicatedException.class)
    public Result CategoryNameDuplicatedExceptionHandler(CategoryNameDuplicatedException exception)  {
        return Result.error(exception.getMessage());
    }
}
